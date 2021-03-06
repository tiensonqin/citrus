(ns citrus.reconciler
  (:require-macros [citrus.macros :as m]))

(defn- queue-effects! [queue f]
  (vswap! queue conj f))

(defn- clear-queue! [queue]
  (vreset! queue []))

(defn- schedule-update! [schedule! scheduled? f]
  (when-let [id @scheduled?]
    (vreset! scheduled? nil)
    (js/cancelAnimationFrame id))
  (vreset! scheduled? (schedule! f)))

(defprotocol IReconciler
  (dispatch! [this event args])
  (dispatch-sync! [this event args]))

(deftype Reconciler [handler effect-handlers state queue scheduled? batched-updates chunked-updates meta]

  Object
  (equiv [this other]
    (-equiv this other))

  IAtom

  IMeta
  (-meta [_] meta)

  IEquiv
  (-equiv [this other]
    (identical? this other))

  IDeref
  (-deref [_]
    (-deref state))

  IWatchable
  (-add-watch [this key callback]
    (add-watch state (list this key)
               (fn [_ _ oldv newv]
                 (when (not= oldv newv)
                   (callback key this oldv newv))))
    this)

  (-remove-watch [this key]
    (remove-watch state (list this key))
    this)

  IHash
  (-hash [this] (goog/getUid this))

  IPrintWithWriter
  (-pr-writer [this writer opts]
    (-write writer "#object [citrus.reconciler.Reconciler ")
    (pr-writer {:val (-deref this)} writer opts)
    (-write writer "]"))

  IReconciler
  (dispatch! [this event args]
    (let [cname (keyword (namespace event))
          seperate-state? (not= cname :citrus)]
      (queue-effects!
       queue
       [cname #(apply (get handler event)
                 (if seperate-state? (get % cname) %)
                 args)])

      (schedule-update!
       batched-updates
       scheduled?
       (fn []
         (let [events @queue
               _ (clear-queue! queue)
               next-state
               (loop [st @state
                      [event & events] events]
                 (if (seq event)
                   (let [[cname ctrl] event
                         effects (ctrl st)]
                     (m/doseq [[id effect] (dissoc effects :state)]
                       (when-let [handler (get @effect-handlers id)]
                         (handler this effect)))
                     (if (contains? effects :state)
                       (let [seperate-state? (not= cname :citrus)]
                         (recur (if seperate-state?
                                  (update st cname merge (:state effects))
                                  (merge st (:state effects))) events))
                       (recur st events)))
                   st))]
           (reset! state next-state))))))

  (dispatch-sync! [this event args]
    (let [effects (apply (get handler event) @state args)
          cname (keyword (namespace event))
          seperate-state? (not= cname :citrus)]
      (m/doseq [[id effect] effects]
        (let [handler (get @effect-handlers id)]
          (cond
            (= id :state) (swap! state (fn [state]
                                         (if seperate-state?
                                           (update state cname merge effect)
                                           (merge state effect))))
            handler (handler this effect)
            :else nil))))))

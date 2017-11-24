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
    (let [ctrl (keyword (namespace event))
          seperate-state? (not= ctrl :citrus)]
      (queue-effects!
       queue
       #(apply (get handler event)
          (if seperate-state? (get @state ctrl) @state)
          args))

      (schedule-update!
       batched-updates
       scheduled?
       (fn []
         (let [effects
               (map (fn [f] (f)) @queue)]
           (clear-queue! queue)
           (let [state-effects (filter :state effects)
                 other-effects (->> effects
                                    (map (fn [effect]
                                           (dissoc effect :state)))
                                    (filter seq))]
             (when (seq state-effects)
               (swap! state #(reduce (fn [agg {cstate :state}]
                                       (if seperate-state?
                                         (update agg ctrl merge cstate)
                                         (merge agg cstate)))
                                     % state-effects)))
             (when (seq other-effects)
               (m/doseq [effects effects]
                 (m/doseq [[id effect] effects]
                   (when-let [handler (get effect-handlers id)]
                     (handler this effect)))))))))))

  (dispatch-sync! [this event args]
    (let [effects (apply (get handler event) @state args)
          ctrl (keyword (namespace event))
          seperate-state? (not= ctrl :citrus)]
      (m/doseq [[id effect] effects]
        (let [handler (get effect-handlers id)]
          (cond
            (= id :state) (swap! state (fn [state]
                                         (if seperate-state?
                                           (update state ctrl merge effect)
                                           (merge state effect))))
            handler (handler this effect)
            :else nil))))))

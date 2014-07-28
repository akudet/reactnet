(ns reactnet.reactor
  "Factories and combinators for FRP style behaviors and eventstreams."
  (:refer-clojure :exclude [concat count delay filter merge map mapcat reduce remove some take])
  (:require [clojure.core :as c]
            [clojure.string :as s]
            [reactnet.scheduler :as sched]
            [reactnet.reactives]
            [reactnet.core :refer :all]
            [reactnet.engines :refer [agent-engine]])
  (:import [clojure.lang PersistentQueue]
           [reactnet.reactives Behavior Eventstream SeqStream]))


;; TODOS
;; - How to prevent scheduled tasks from accumulating?
;; - Implement unsubscribe
;; - Implement proper error handling
;; - Implement execution-modes (sync, async, future)
;; - Implement expression lifting


;; ===========================================================================
;; EXPERIMENTAL NEW REACTOR API IMPL


(alter-var-root #'reactnet.core/*engine*
                (fn [_]
                  (agent-engine (make-network "default" []))))

(defn behavior
  ([label]
     (behavior label nil))
  ([label value]
     (Behavior. label
                (atom [value (now)])
                (atom true))))


(defn eventstream
  [label]
  (Eventstream. label
                (atom {:queue (PersistentQueue/EMPTY)
                       :last-occ nil
                       :completed false})
                1000))

(defn seqstream
  [xs]
  (assoc (SeqStream. (atom {:seq (seq xs)
                            :last-occ nil})
                     true)
    :label "seq"))


(defn eventstream?
  [reactive]
  (or (instance? Eventstream reactive)
      (instance? SeqStream reactive)))


(defn behavior?
  [reactive]
  (instance? Behavior reactive))

(defn async
  [f]
  {:async f})


(defn unpack-fn
  [fn-or-map]
  (if-let [f (:async fn-or-map)]
    [make-async-link-fn f]
    [make-sync-link-fn fn-or-map]))


(defn fn-spec?
  [f]
  (or (instance? clojure.lang.IFn f)
      (and (map? f) (instance? clojure.lang.IFn (:async f)))))


;; ---------------------------------------------------------------------------
;; Enqueuing reactives and switching bewteen them

(defn- make-reactive-queue
  ([output]
     (make-reactive-queue output nil))
  ([output reactives]
     {:queue (vec reactives)
      :input nil
      :output output}))


(defn- switch-reactive
  [{:keys [queue input output] :as queue-state} q-atom]
  (let [uncompleted (c/remove completed? queue)
        r           (first uncompleted)]
    (if (and r (or (nil? input) (completed? input)))
      (assoc queue-state
        :queue (vec (rest uncompleted))
        :input r
        :add [(make-link "temp" [r] [output]
                         :complete-fn
                         (fn [_ r]
                           (c/merge (swap! q-atom switch-reactive q-atom)
                                    {:remove-by #(= [r] (link-inputs %))})))])
      queue-state)))


(defn- enqueue-reactive
  [queue-state q-atom r]
  (switch-reactive (update-in queue-state [:queue] conj r) q-atom))




;; ---------------------------------------------------------------------------
;; Queue to be used with an atom or agent

(defn make-queue
  [max-size]
  {:queue (PersistentQueue/EMPTY)
   :dequeued []
   :max-size max-size})


(defn- enqueue
  [{:keys [queue dequeued max-size] :as q} v]
  (assoc q :queue
         (conj (if (>= (c/count queue) max-size)
                 (pop queue)
                 queue)
               v)))


(defn- dequeue
  [{:keys [queue dequeued] :as q}]
  (if-let [v (first queue)]
    (assoc q
      :queue (pop queue)
      :dequeued [v])
    (assoc q
      :dequeued [])))


(defn- dequeue-all
  [{:keys [queue dequeued] :as q}]
  (assoc q
    :queue (empty queue)
    :dequeued (vec queue)))


;; ---------------------------------------------------------------------------
(defonce scheduler (sched/scheduler 5))


(defn halt!
  "Cancel all scheduled tasks."
  []
  (sched/cancel-all scheduler))

;; ---------------------------------------------------------------------------
;; More constructors of reactives

(defn just
  [x]
  (seqstream [x]))


(defn sample
  [millis f-or-ref-or-value]
  (let [sample-fn (cond
                   (fn? f-or-ref-or-value)
                   #(try (f-or-ref-or-value)
                         (catch Exception ex
                           (do (.printStackTrace ex)
                               ;; TODO what to push in case f fails?
                               ex)))
                   (instance? clojure.lang.IRef f-or-ref-or-value)
                   #(deref f-or-ref-or-value)
                   :else
                   (constantly f-or-ref-or-value))
        new-r      (eventstream "sample")
        engine     *engine*
        task       (sched/interval scheduler millis
                                   #(push! engine new-r (sample-fn)))]
    new-r))


(defn timer
  [millis]
  (let [ticks  (atom 0)
        new-r  (eventstream "timer")
        engine *engine*]
    (sched/interval scheduler millis millis
                    #(push! engine new-r (swap! ticks inc)))
    new-r))



;; ---------------------------------------------------------------------------
;; Some combinators


(defn- derive-new
  [factory-fn label inputs
   & {:keys [link-fn complete-fn error-fn]
      :or {link-fn default-link-fn}}]
  {:pre [(seq inputs)]}
  (let [new-r   (factory-fn label)]
    (add-links! *engine* (make-link label inputs [new-r]
                                   :link-fn link-fn
                                   :complete-fn complete-fn
                                   :error-fn error-fn
                                   :complete-on-remove [new-r]))
    new-r))

(declare match)


(defn amb
  [& reactives]
  {:pre [(every? reactive? reactives)]
   :post [(reactive? %)]}
  (let [new-r   (eventstream "amb")
        f       (fn [{:keys [input-reactives input-rvts] :as input}]
                  (let [r (first input-reactives)]
                    {:remove-by #(= (link-outputs %) [new-r])
                     :add [(make-link "amb-selected" [r] [new-r] :complete-on-remove [new-r])]
                     :output-rvts (single-value (fvalue input-rvts) new-r)}))
        links   (->> reactives (c/map #(make-link "amb-tentative" [%] [new-r] :link-fn f)))]
    (apply (partial add-links! *engine*) links)
    new-r))


(defn any
  ([reactive]
     (any identity reactive))
  ([pred reactive]
     (match pred true false reactive)))


(defn buffer
  [no millis reactive]  
  {:pre [(number? no) (number? millis) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [b      (atom {:queue [] :dequeued nil})
        task   (atom nil)
        enq    (fn [{:keys [queue] :as q} x]
                 (if (and (> no 0) (>= (c/count queue) no))
                   (assoc q :queue [x] :dequeued queue)
                   (assoc q :queue (conj queue x) :dequeued nil)))
        deq    (fn [{:keys [queue] :as q}]
                 (assoc q :queue [] :dequeued queue))
        engine *engine*]
    (derive-new eventstream "buffer" [reactive]
                :link-fn
                (fn [{:keys [input-rvts output-reactives] :as input}]
                  (let [output         (first output-reactives)
                        {vs :dequeued
                         queue :queue} (swap! b enq (fvalue input-rvts))]
                    (when (and (= 1 (c/count queue)) (> millis 0))
                      (swap! task (fn [_]
                                    (sched/once scheduler millis
                                                (fn []
                                                  (let [vs (:dequeued (swap! b deq))]
                                                    (when (seq vs)
                                                      (push! engine output vs))))))))
                    (if (seq vs)
                      (do (some-> task deref sched/cancel)
                          (make-result-map input vs)))))
                :complete-fn
                (fn [link r]
                  (let [vs (:queue @b)]
                    (some-> task deref sched/cancel)
                    (if (seq vs)
                      {:output-rvts (single-value vs (-> link link-outputs first))}))))))

(defn buffer-t
  [millis reactive]
  (buffer -1 millis reactive))


(defn buffer-c
  [no reactive]
  (buffer no -1 reactive))


(defn concat
  [& reactives]
  {:pre [(every? reactive? reactives)]
   :post [(reactive? %)]}
  (let [new-r   (eventstream "concat")
        state   (atom (make-reactive-queue new-r reactives))
        link    (-> (swap! state switch-reactive state) :add first)]
    (add-links! *engine* link)
    new-r))


(defn count
  [reactive]
  {:pre [(reactive? reactive)]
   :post [(reactive? %)]}
  (let [c (atom 0)]
    (derive-new eventstream "count" [reactive]
                :link-fn
                (fn [{:keys [input-rvts] :as input}]
                  (make-result-map input (swap! c inc))))))


(defn debounce
  [millis reactive]
  {:pre [(number? millis) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [task (atom nil)]
    (derive-new eventstream "debounce" [reactive]
                :link-fn
                (fn [{:keys [input-rvts output-reactives] :as input}]
                  (let [output (first output-reactives)
                        v      (fvalue input-rvts)
                        old-t  @task
                        engine *engine*
                        new-t  (sched/once scheduler millis #(push! engine output v))]
                    (when (and old-t (sched/pending? old-t))
                      (sched/cancel old-t))
                    (reset! task new-t)
                  nil)))))


(defn delay
  [millis reactive]
  {:pre [(number? millis) (reactive? reactive)]
   :post [(reactive? %)]}
  (derive-new eventstream "delay" [reactive]
              :link-fn
              (fn [{:keys [input-rvts output-reactives] :as input}]
                (let [output (first output-reactives)
                      v      (fvalue input-rvts)
                      engine *engine*]
                  (sched/once scheduler millis #(push! engine output v))
                  nil))))

(defn every
  ([reactive]
     (every identity reactive))
  ([pred reactive]
     (match (complement pred) false true reactive)))


(defn filter
  [pred reactive]
  {:pre [(fn-spec? pred) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [[make-link-fn f] (unpack-fn pred)]
    (derive-new eventstream "filter" [reactive]
                :link-fn
                (make-link-fn f (fn [{:keys [input-rvts] :as input} v ex]
                                  (if v
                                    (make-result-map input
                                                     (fvalue input-rvts)
                                                     ex)))))))


(defn hold
  [reactive]
  (derive-new behavior "hold" [reactive]))


(defn map
  [f & reactives]
  {:pre [(fn-spec? f) (every? reactive? reactives)]
   :post [(reactive? %)]}
  (let [[make-link-fn f] (unpack-fn f)]
    (derive-new eventstream "map" reactives
                :link-fn
                (make-link-fn f make-result-map))))


(defn mapcat'
  [f reactive]
  {:pre [(fn-spec? f) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [[make-link-fn f] (unpack-fn f)
        new-r    (eventstream "mapcat'")
        state    (atom (make-reactive-queue new-r)) ]
    (add-links! *engine* (make-link "mapcat'" [reactive] []
                                   :link-fn
                                   (fn [{:keys [input-rvts] :as input}]
                                     (let [r (f (fvalue input-rvts))]
                                       (swap! state enqueue-reactive state r)))
                                   :complete-on-remove [new-r]))
    new-r))


(defn mapcat
  [f & reactives]
  {:pre [(fn-spec? f) (every? reactive? reactives)]
   :post [(reactive? %)]}
  (let [[make-link-fn f] (unpack-fn f)]
    (derive-new eventstream "mapcat" reactives
                :link-fn
                (make-link-fn f (fn [input vs ex]
                                  (assoc input
                                    :output-rvts (if-not ex
                                                   (enqueue-values vs (-> input :output-reactives first)))
                                    :exception ex))))))


(defn match
  ([pred match-value default-value reactive]
  {:pre [(fn-spec? pred) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [[make-link-fn pred] (unpack-fn pred)]
    (derive-new eventstream "match" [reactive]
                :link-fn
                (make-link-fn pred
                              (fn [{:keys [output-reactives]} v ex]
                                (if v
                                  {:output-rvts (single-value match-value (first output-reactives))
                                   :remove-by #(= output-reactives (link-outputs %))})))
                :complete-fn (fn [l r]
                               {:output-rvts (single-value default-value (-> l link-outputs first))})))))


(defn merge
  [& reactives]
  {:pre [(every? reactive? reactives)]
   :post [(reactive? %)]}
  (let [new-r   (eventstream "merge")
        links   (->> reactives (c/map #(make-link "merge" [%] [new-r])))]
    (apply (partial add-links! *engine*) links)
    new-r))


(defn reduce
  [f initial-value reactive]
  {:pre [(fn-spec? f) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [[make-link-fn f] (unpack-fn f)
        accu             (atom initial-value)]
    (derive-new eventstream "reduce" [reactive]
                :link-fn
                (make-link-fn (fn [v] (swap! accu f v))
                              (constantly {}))
                :complete-fn
                (fn [l r]
                  {:output-rvts (single-value @accu (-> l link-outputs first))}))))


(defn scan
  [f initial-value reactive]
  {:pre [(reactive? reactive)]
   :post [(reactive? %)]}
  (let [[make-link-fn f] (unpack-fn f)
        accu             (atom initial-value)]
    (derive-new eventstream "scan" [reactive]
                :link-fn
                (make-link-fn (fn [v] (swap! accu f v))
                              make-result-map))))


(defn startwith
  [start-reactive reactive]
  (concat start-reactive reactive))


(defn switch
  [reactive]
  {:pre [(reactive? reactive)]
   :post [(reactive? %)]}
  (let [new-r (eventstream "switch")]
    (add-links! *engine*
                (make-link "switcher" [reactive] []
                           :link-fn
                           (fn [{:keys [input-rvts] :as input}]
                             (let [r (fvalue input-rvts)]
                               {:remove-by #(= (link-outputs %) [new-r])
                                :add (if (reactive? r)
                                       [(make-link "switch" [r] [new-r])])}))))
    new-r))


(defn subscribe
  [f reactive]
  {:pre [(fn-spec? f) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [[make-link-fn f] (unpack-fn f)]
    (add-links! *engine* (make-link "subscriber" [reactive] []
                                    :link-fn (make-link-fn f (constantly {}))))
    reactive))


(defn swap-conj!
  [a reactive]
  (subscribe (partial swap! a conj) reactive))


(defn take
  [no reactive]
  {:pre [(number? no) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [new-r   (eventstream "take")
        c       (atom no)]
    (add-links! *engine*
                (make-link "take" [reactive] [new-r]
                           :link-fn
                           (fn [{:keys [input-rvts] :as input}]
                             (let [r (make-result-map input (fvalue input-rvts))]
                               (if (>= 0 (swap! c dec))
                                 (assoc r
                                   :remove-by #(= (link-outputs %) [new-r]))
                                 r)))
                           :complete-on-remove [new-r]))
    new-r))


(defn throttle
  [f millis max-queue-size reactive]
  {:pre [(fn-spec? f) (number? millis) (number? max-queue-size) (reactive? reactive)]
   :post [(reactive? %)]}
  (let [queue-atom (atom (make-queue max-queue-size))
        new-r  (derive-new eventstream "throttle" [reactive]
                           :link-fn
                           (fn [{:keys [input-rvts] :as input}]
                             (let [v (fvalue input-rvts)]
                               ;; TODO enqueue silently drops items, fix this
                               (swap! queue-atom enqueue v)
                               nil)))
        engine *engine*]
    (sched/interval scheduler millis millis
                    #(let [vs (:dequeued (swap! queue-atom dequeue-all))]
                       (when-not (empty? vs) (push! engine new-r (f vs)))))
    new-r))



;; Howto define link functions succinctly?
#_ (def b (rmap {:f foobar
                 :link-fn-factory [sync, future, go]
                 :result-fn (fn [])
                 :error-fn (fn []) }))



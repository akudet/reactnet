(ns reactnet.core
  "Implementation of a propagation network."
  (:require [clojure.string :as s]
            [reactnet.debug :as dbg]
            [reactnet.monitor :as mon])
  (:import [java.lang.ref WeakReference]
           [java.util WeakHashMap]))

;; TODO
;; fix weakref test
;; support IDeref of netrefs

;; ---------------------------------------------------------------------------
;; Concepts

;; Reactive:
;; Serves as abstraction of event streams and behaviors.

(defprotocol IReactive
  (next-value [r]
    "Returns the next value-timestamp pair of the reactive r without
    consuming it.")
  (available? [r]
    "Returns true if the reactive r will provide a value upon
    next-value / consume.")
  (pending? [r]
    "Returns true if r contains values that wait for being consumed.")
  (completed? [r]
    "Returns true if the reactive r will neither accept nor return a new value.")
  (consume! [r]
    "Returns the next value-timestamp pair of the reactive r and may
    turn the state into unavailable.")
  (deliver! [r value-timestamp-pair]
    "Sets/adds a pair of value and timestamp to r, returns true if a
  propagation of the value should be triggered."))

;; Link:
;; A map connecting input and output reactives via a function.
;;   :label               Label for pretty printing
;;   :inputs              Input reactives
;;   :outputs             Output reactives, each wrapped in WeakReference
;;   :link-fn             A link function [Result -> Result] (see below)
;;   :error-fn            An error handling function [Result -> Result] (see below)
;;   :complete-fn         A function [Link Reactive -> Result] called when one of the
;;                        input reactives becomes completed
;;   :complete-on-remove  A seq of reactives to be completed when this link is removed
;;   :executor            The executor to use for invoking the link function

;; Link function:
;;  A function [Result -> Result] that takes a Result map containing
;;  input values and returns a Result map or nil.

;; Error handling function:
;;  A function [Result -> Result] that takes the Result containing an
;;  exception. It may return a new Result map (see below) or nil.

;; RVT:
;;  A nested pair [r [v t]] representing a value v assigned to the
;;  Reactive r at time t.
;;  Something called *rvts is a sequence of those pairs.

;; Result:
;; A map passed into / returned by a link function with the following entries
;;   :input-reactives     The links input reactives
;;   :output-reactives    The links output reactives
;;   :input-rvts          A seq of RVTs
;;   :output-rvts         A seq of RVTs
;;   :no-consume          Signals that the input values must not get consumed
;;   :exception           Exception, or nil if output-rvts is valid
;;   :add                 A seq of links to be added to the network
;;   :remove-by           A predicate that matches links to be removed
;;                        from the network
;;   :dont-complete       A seq of reactives for which to increase the
;;                        alive counter
;;   :allow-complete      A seq of reactives for which to decrease the
;;                        alive counter

;; Network:
;; A map containing
;;   :id                  A string that identifies the network for logging purposes
;;   :links               Collection of links
;;   :rid-map             WeakHashMap {Reactive -> rid}
;;                        rid = reactive id (derived)
;;   :level-map           Map {rid -> topological-level} (derived)
;;   :links-map           Map {rid -> Seq of links} (derived)
;;   :alive-map           Map {rid -> c} of reactives, where c is an integer
;;                        which is increased upon dont-complete and
;;                        decreased upon allow-complete. If c becomes 0
;;                        the corresponding reactive is auto completed
;;   :next-rid            Atom containing the next rid to assign
;;   :completed           A set containing completed reactives
;;   :removes             An integer counting how many link removals happened
;;                        in order to decide when to rebuild the level-map

;; Stimulus
;; A map containing data that is passed to enq/update-and-propagate to
;; start an update/propagation cycle on a network.
;;   :results             A seq of Result maps
;;   :exec                A vector containing a function [network & args -> network]
;;                        and additional args
;;   :rvt-map             A map {Reactive -> [v t]} of values to propagate

;; NetworkRef:
;; Serves as abstraction of how the network is stored and
;; propagation/updates to it are scheduled.

(defprotocol INetworkRef
  (enq [netref stimulus]
    "Enqueue a new update/propagation cycle that will process a
  stimulus containing a seq of result maps, remove links by predicate,
  add new links and propagate the values in the {Reactive -> [v t]}
  map.

  Returns the netref.

  An implementation should delegate to the update-and-propagate
  function.")
  (scheduler [netref]
    "Return the scheduler.")
  (network [netref]
    "Return the network map.")
  (monitors [netref]
    "Return the monitors map."))



(def ^:dynamic *netref* "A reference to a current thread-local network." nil)
(def rebuild-threshold "The number of removed links before a rebuild is due." 100)


;; Executor
;; Used to execute link functions in another thread / asynchronously.

(defprotocol IExecutor
  (execute [e netref f]
    "Execute a no-arg function f on a different thread with the dynamic var
    *netref* bound to netref."))



;; ---------------------------------------------------------------------------
;; Misc utilities

(defn ^:no-doc dissect
  "Returns a pair of vectors, first vector contains the xs for
  which (pred x) returns true, second vector the other xs."
  [pred xs]
  (if (and (set? pred) (empty? pred))
    [[] (vec xs)]
    (let [[txs fxs] (reduce (fn [[txs fxs] x]
                              (if (pred x)
                                [(conj! txs x) fxs]
                                [txs (conj! fxs x)]))
                            [(transient []) (transient [])]
                            xs)]
      [(persistent! txs) (persistent! fxs)])))


(defn- any-pred?
  "Returns true if x matches any of the given predicates."
  [preds x]
  (when (seq preds)
    (or ((first preds) x)
        (recur (next preds) x))))


;; ---------------------------------------------------------------------------
;; Functions that operate on reactives.

(defn reactive?
  "Returns true if r satisfies IReactive."
  [r]
  (satisfies? IReactive r))


;; ---------------------------------------------------------------------------
;; Functions to extract values from RVTs


(defn value
  "Returns the value from an RVT."
  [[r [v t]]]
  v)


(defn fvalue
  "Returns the value from the first item of an RVT seq."
  [rvts]
  (-> rvts first value))


(defn values
  "Returns a vector with all extracted values from an RVT seq."
  [rvts]
  (mapv value rvts))


;; ---------------------------------------------------------------------------
;; Functions to produce RVT seqs


(defn now
  "Returns the current epoch time in milliseconds."
  []
  (System/currentTimeMillis))


(defn single-value
  "Returns a sequence with exactly one RVT pair assigned to reactive
  r."
  [v r]
  {:pre [(reactive? r)]}
  [[r [v (now)]]])


(defn broadcast-value
  "Returns an RVT seq where the value v is assigned to every reactive
  in rs."
  [v rs]
  {:pre [(every? reactive? rs)]}
  (let [t (now)]
    (for [r rs] [r [v t]])))


(defn zip-values
  "Returns an RVT seq where values are position-wise assigned to
  reactives."
  [vs rs]
  {:pre [(every? reactive? rs)]}
  (let [t (now)]
    (map (fn [r v] [r [v t]]) rs vs)))


(defn enqueue-values
  "Returns an RVT seq where all values in vs are assigned to the same
  reactive r."
  [vs r]
  {:pre [(reactive? r)]}
  (let [t (now)]
    (for [v vs] [r [v t]])))



;; ---------------------------------------------------------------------------
;; Functions on links

(defn- wref-wrap
  "Wraps all xs in a WeakReference and returns a vector of those."
  [xs]
  (mapv #(WeakReference. %) xs))


(defn- wref-unwrap
  "Unwraps all WeakReferences and returns the result as vector."
  [wrefs]
  (mapv #(.get %) wrefs))


(defn link-outputs
  "Returns the output-reactives of a link, unwrapping them from WeakReferences."
  [link]
  (-> link :outputs wref-unwrap))


(defn link-inputs
  "Returns the input-reactives of a link."
  [link]
  (-> link :inputs))



;; ---------------------------------------------------------------------------
;; Factories


(defn default-link-fn
  "A link-function that implements a pass-through of inputs to outputs.
  If there is more than one input reactive, zips values of all inputs
  into a vector, otherwise takes the single value.  Returns a Result
  map with the extracted value assigned to all output reactives."
  [{:keys [input-rvts input-reactives output-reactives] :as input}]
  (let [v (case (count input-reactives)
            0 nil
            1 (fvalue input-rvts)
            (values input-rvts))]
    (assoc input :output-rvts (broadcast-value v output-reactives))))


(defn make-link
  "Creates and returns a new Link map. 

  Label is an arbitrary text, inputs and outputs are sequences of
  reactives.

  Output reactives are kept using WeakReferences.
  
  The link-fn is a Link function [Result -> Result] which is called to
  produce a result from inputs-rvts. Defaults to default-link-fn.
  
  The error-fn is a function [Result -> Result] which is called when
  an exception was thrown by the Link function. Defaults to nil.

  The complete-fn is a function [Link Reactive -> Result] which is called for
  each input reactive that completes. Defaults to nil.

  The sequence complete-on-remove contains all reactives that should be
  completed when this Link is removed from the network."
  [label inputs outputs
   & {:keys [link-fn error-fn complete-fn complete-on-remove executor]
      :or {link-fn default-link-fn}}]
  {:pre [(seq inputs)
         (every? reactive? (concat inputs outputs))]}
  {:label label
   :inputs inputs
   :outputs (wref-wrap outputs)
   :link-fn link-fn
   :error-fn error-fn
   :complete-fn complete-fn
   :complete-on-remove complete-on-remove
   :executor executor})


(declare rebuild)

(defn make-network
  "Returns a new network."
  [id links]
  (rebuild {:id id
            :dont-complete {}
            :next-rid (atom 1)} links))



;; ---------------------------------------------------------------------------
;; Pretty printing

(defn ^:no-doc str-react
  [r]
  (str (if (completed? r) "C " "  ") (:label r) ":" (pr-str (next-value r))))

(declare dead?)

(defn ^:no-doc str-link  
  [l]
  (str " [" (s/join " " (map :label (link-inputs l)))
       "] -- " (:label l) " --> ["
       (s/join " " (mapv :label (link-outputs l)))
       "] " (cond
             (every? available? (->> l link-inputs (remove nil?))) "READY"
             (dead? l) "DEAD"
             :else "incomplete")))


(defn ^:no-doc str-rvalue
  [[r [v timestamp]]]
  (str (:label r) ": " v))


(defn ^:no-doc str-rvalues
  [[r vs]]
  (str (:label r) ": [" (->> vs (map first) (s/join ", ")) "]"))


;; ---------------------------------------------------------------------------
;; Debug logging

(defn ^:no-doc log-text
  [type & xs]
  (dbg/log {:type type
            :text (apply print-str xs)}))


(defn ^:no-doc log-links
  [type links]
  (doseq [l links]
    (dbg/log {:type type
              :l (:label l)
              :inputs (map :label (link-inputs l))
              :outputs (map :label (link-outputs l))})))


(defn ^:no-doc log-rvts
  [type rvts]
  (doseq [[r [v t]] rvts]
    (dbg/log {:type type
             :r (:label r)
             :v v
             :t t})))

;; ---------------------------------------------------------------------------
;; Getting information about the reactive graph

(defn ^:no-doc ready?
  "Returns true for a link if
  - all inputs are available,
  - at least one output is not completed."
  [link]
  (let [inputs (link-inputs link)
        outputs (link-outputs link)]
    (and (every? available? inputs)
         (remove completed? outputs))))


(defn ^:no-doc dead?
  "Returns true for a link if it has no inputs, or at least one of it's
  inputs is completed, or all outputs are completed. Having no outputs does
  not count as 'all outputs completed'."
  [link]
  (let [inputs (link-inputs link)
        outputs (link-outputs link)]
    (or (and (seq outputs)
             (->> outputs
                  (remove nil?)
                  (every? completed?)))
        (some completed? inputs))))


(defn ^:no-doc pending-reactives
  "Returns a seq of pending reactives."
  [{:keys [rid-map]}]
  (->> rid-map keys (filter pending?) doall))


(defn ^:no-doc completed-reactives
  "Returns a seq of completed reactives."
  [{:keys [rid-map]}]
  (->> rid-map keys (filter completed?) doall))


(defn ^:no-doc pending-and-completed-reactives
  "Returns a pair [pending-reactives completed-reactives]."
  [{:keys [rid-map]}]
  (reduce (fn [[prs crs] r]
            (cond
             (pending? r) [(conj prs r) crs]
             (completed? r) [prs (conj crs r)]
             :else [prs crs]))
          [[] []]
          (keys rid-map)))


(defn ^:no-doc dependent-links
    "Returns links where r is an input of."
  [{:keys [rid-map links-map]} r]
  (->> r (get rid-map) (get links-map)))


;; ---------------------------------------------------------------------------
;; Modifying the network


(defn- update-rid-map!
  "Adds input and output reactives to rid-map.
  Returns an updated network."
  [{:keys [next-rid rid-map] :as n} link]
  (doseq [r (concat (link-outputs link)
                    (link-inputs link))]
    (when-not (.get rid-map r)
      (let [rid (swap! next-rid inc)]
        (dbg/log {:type "rid-map" :r (:label r) :rid rid})
        (.put rid-map r rid))))
  n)


(defn- update-complete-sets
  "Adds reactives in dont-complete and allow-complete to the
  corresponding entries in the network. Returns an updated network."
  [n dont-complete-rs allow-complete-rs]
  (-> n
      (update-in [:dont-complete] concat dont-complete-rs)
      (update-in [:allow-complete] concat allow-complete-rs)))


(defn- adjust-downstream-levels
  "Takes reactive ids and does a breadth first tree walk to update level-map.
  Returns a level-map."
  [rid-map links-map level-map rids level]
  (loop [lm      level-map
         crs     rids
         lv      level
         visited #{}]
    (if-let [rids (seq (remove visited crs))]
      (let [ls (mapcat links-map rids)]
        (recur (merge lm
                      (into {} (map vector rids (repeat lv)))
                      (into {} (map vector ls (repeat (inc lv)))))
               (->> ls
                    (mapcat link-outputs)
                    (map (partial get rid-map)))
               (+ lv 2)
               (into visited rids)))
      lm)))


(defn- add-link
  "Adds a link to the network. Returns an updated network."
  [{:keys [rid-map links links-map level-map alive-map] :as n} l]
  (update-rid-map! n l)
  (let [input-rids  (->> l link-inputs (map (partial get rid-map)))
        output-rids (->> l link-outputs (map (partial get rid-map)))
        level-map   (reduce (fn [m i]
                              (if-not (get m i)
                                (assoc m i 1)
                                m))
                            level-map
                            input-rids)
        link-level  (->> input-rids
                         (map level-map)
                         (apply max)
                         inc)
        links-map  (reduce (fn [m i]
                             (if-let [lset (get m i)]
                               (assoc m i (conj lset l))
                               (assoc m i #{l})))
                           links-map
                           input-rids)
        alive-map  (reduce (fn [m i]
                             (if-not (get m i)
                               (assoc m i 1)
                               m))
                           alive-map
                           (concat input-rids output-rids))]
    (assoc n
      :links (conj links l)
      :links-map links-map
      :alive-map alive-map
      :level-map (adjust-downstream-levels rid-map
                                           links-map
                                           (assoc level-map l link-level)
                                           output-rids
                                           (inc link-level)))))


(defn- rebuild
  "Takes a network and a set of links and re-calculates rid-map,
  links-map and level-map. Preserves other existing entries. Returns an
  updated network."
  [{:keys [id rid-map] :as n} links]
  (when rid-map
    (doseq [r (completed-reactives n)]
      (.remove rid-map r)))
  (reduce add-link (assoc n
                     :rid-map (or rid-map (WeakHashMap.))
                     :removes 0
                     :links []
                     :links-map {}
                     :level-map {})
          links))


(defn- rebuild-if-necessary
  [{:keys [removes links] :as n}]
  (if (> (or removes 0) rebuild-threshold)
    (rebuild n links)
    n))


(defn- remove-links
  "Removes links specified by predicate or set.
  Returns an updated network."
  ([n pred]
     (remove-links n pred true))
  ([{:keys [rid-map links links-map removes] :as n} pred completion?]
     (if pred
       (let [[links-to-remove
              remaining-links] (dissect pred links)]
         (if (seq links-to-remove)
           (let [_          (log-links "remove" links-to-remove)
                 n          (if completion?
                              (update-complete-sets n nil (mapcat :complete-on-remove links-to-remove))
                              n)
                 input-rids (->> links-to-remove
                                 (mapcat link-inputs)
                                 (map (partial get rid-map)))
                 links-map  (reduce (fn [lm i]
                                      (let [lset (apply disj (get lm i) links-to-remove)]
                                        (if (empty? lset)
                                          (dissoc lm i)
                                          (assoc lm i lset))))
                                    links-map
                                    input-rids)]
             (assoc n
               :links remaining-links
               :removes (+ (count links-to-remove) (or removes 0))
               :links-map links-map))
           n))
       n)))


(defn- apply-exec
  "Takes a network and a seq where the first item is a function f and
  the other items are arguments. Invokes (f n arg1 arg2 ...). f must
  return a network. Omits invocation of f if it is nil."
  [n [f & args]]
  (if f
    (apply (partial f n) args)
    n))


(defn- auto-complete
  "Updates networks alive-map and automatically delivers ::completed
  to all reactives whose alive counter becomes 0. Returns an updated network."
  [{:keys [rid-map alive-map allow-complete dont-complete completed] :as n}]
  (let [[alive-map
         completables] (reduce (fn [[m rs] [r diff]]
                                 (let [rid (get rid-map r)
                                       c   (get m rid)]
                                   (if c
                                     (let [c (+ diff c)]
                                       #_(dbg/log {:type "alive-map" :r (:label r) :rid rid :diff diff :c c})
                                       (if (> c 0)
                                         [(assoc m rid c) rs]
                                         [(dissoc m rid) (conj rs r)]))
                                     (do (dbg/log {:type "warning" :r (:label r) :rid rid}) [m rs]))))
                               [alive-map nil]
                               (concat (map vector dont-complete (repeat 1))
                                       (map vector allow-complete (repeat -1))))
         vt            [::completed (now)]]
    (doseq [r completables]
      (deliver! r vt))
    (let [completed (into (or completed #{})
                          (filter completed? completables))]
      (assoc n
        :alive-map alive-map
        :allow-complete nil
        :dont-complete nil
        :completed completed
        :unchanged? (and (:unchanged? n) (empty? completed))))))


(defn- add-links
  "Conjoins new-links to the networks links. Returns an updated
  network."
  [n new-links]
  (when (seq new-links)
    (log-links "add" new-links))
  (reduce add-link n new-links))


(defn- remove-links-pred
  "Takes a network, a seq of completed reactives and a seq of result
  maps and returns a predicate to remove links."
  [n completed-rs results]
  (let [links-to-remove (->> completed-rs
                             (mapcat (partial dependent-links n))
                             set)
        remove-bys      (->> results
                             (map :remove-by)
                             (remove nil?))
        preds           (if (seq links-to-remove)
                          (conj remove-bys links-to-remove)
                          remove-bys)]
    (if (seq preds)
      (partial any-pred? preds))))


(defn- replace-link-error-fn
  "Match links by pred, attach the error-fn and replace the
  link. Returns an updated network."
  [{:keys [links] :as n} pred error-fn]
  (->> links
       (filter pred)
       (map #(assoc % :error-fn error-fn))
       (reduce add-link (remove-links n pred false))))


;; ---------------------------------------------------------------------------
;; Propagation within network


(defn- handle-exception
  "Invokes the links error-fn function and returns its Result map, or
  prints stacktrace if the link has no error-fn."
  [{:keys [error-fn] :as link} {:keys [exception] :as result}]
  (when exception
    (if error-fn
      (error-fn result)
      (.printStackTrace exception))))


(defn- safely-exec-link-fn
  "Execute link-fn, catch exception and return a Result map that
  merges input with link-fn result and / or error-fn result."
  [{:keys [link-fn] :as link} input]
  (let [result        (try (link-fn input)
                           (catch Exception ex {:exception ex
                                                :dont-complete nil}))
        error-result  (handle-exception link (merge input result))]
    (merge input result error-result)))


(defn- next-values
  "Peeks all values from reactives, without consuming them. 
  Returns a map {Reactive -> [value timestamp]}."
  [reactives]
  (reduce (fn [rvt-map r]
            (assoc rvt-map r (next-value r)))
          {}
          reactives))


(defn- deliver-values!
  "Updates all reactives from the reactive-values map and returns a
  pair of [pending-reactives completed-reactives]."
  [rvt-map]
  (doseq [[r vt] rvt-map]
    (if (completed? r)
      (when (not= (first vt) ::completed)
        (log-text "warning" "Trying to deliver" vt "into completed" (:label r)))
      (try (deliver! r vt)
           (catch IllegalStateException ex
             (enq *netref* {:rvt-map {r vt}})))))
  (let [rs (map first rvt-map)]
    [(filter pending? rs)
     (filter completed? rs)]))


(defn- consume-values!
  "Consumes all values from reactives contained in evaluation results. 
  Returns a seq of completed reactives."
  [results pending-links]
  (let [no-consume (->> pending-links
                        (mapcat link-inputs)
                        (set))
        reactives (->> results
                       (remove :no-consume)
                       (mapcat :input-reactives)
                       (remove no-consume)
                       (set))]
    (doseq [r reactives]
      (consume! r))
    (->> results
         (mapcat :input-reactives)
         (set)
         (filter completed?))))


(defn- eval-link
  "Evaluates one link (possibly using the links executor), returning a Result map."
  [rvt-map {:keys [link-fn level executor] :as link}]
  (let [inputs  (link-inputs link)
        outputs (->> link link-outputs (remove nil?))
        input   {:link link
                 :input-reactives inputs 
                 :input-rvts (for [r inputs] [r (rvt-map r)])
                 :output-reactives outputs
                 :output-rvts nil}
        netref  *netref*]
    (if executor
      (do (log-links "async" [link])
          (execute executor
                   netref
                   (fn []
                     (let [result-map (safely-exec-link-fn link input)]
                       ;; send changes / values to netref
                       #_(log-rvts "async out" (:output-rvts result-map))
                       (enq netref {:results [result-map {:allow-complete (set outputs)}]}))))
          (assoc input :dont-complete (set outputs)))
      (do (log-links "sync" [link])
          (safely-exec-link-fn link input)))))


(defn- eval-links
  "Evaluates all links for pending reactives and returns a vector
  [level pending-links link-results completed-reactives]."
  [{:keys [rid-map links-map level-map] :as n} pending-links pending-rs completed-rs]
  (mon/duration
   (monitors *netref*)
   'eval-links
   (let [available-links (->> pending-rs
                              (mapcat (partial dependent-links n))
                              (concat pending-links)
                              (sort-by level-map (comparator <))
                              (distinct)
                              (filter ready?))]
     (log-links "ready" available-links)
     (if (seq available-links)
       (let [level           (or (-> available-links first level-map) 0)             
             same-level?     (fn [l] (= (level-map l) level))
             [current-links
              pending-links] (dissect same-level? available-links)
             rvt-map         (->> current-links
                                  (mapcat link-inputs)
                                  distinct
                                  next-values)
             _               (log-rvts "input" rvt-map)
             link-results    (->> current-links
                                  (map (partial eval-link rvt-map))
                                  doall)
             completed-rs    (concat completed-rs (consume-values! link-results pending-links))]
         [level pending-links link-results completed-rs])
       [0 pending-links nil completed-rs]))))


(defn- eval-complete-fns
  "Takes completed input reactives, calls complete-fn for each
  link and reactive and returns the results."
  [{:keys [rid-map links links-map] :as n} completed-rs]
  (let [results  (for [r completed-rs
                       [l f] (->> r (dependent-links n)
                                  (map (juxt identity :complete-fn))) :when f]
                   (do (dbg/log {:type "compl-fn" :r (:label r) :l (:label l)})
                       (f l r)))]
    (remove nil? results)))


(declare propagate-downstream)


(defn- update-from-results
  "Takes results from eval-links, propagates values up- and downstream
  and applies network changes (add/remove links, complete reactives
  etc). Returns an updated network."
  [{:keys [rid-map level-map] :as n} [level pending-links link-results completed-rs]]
  (let [compl-results (eval-complete-fns n completed-rs)
        results       (concat link-results compl-results)]
    (if (seq results)
      (let [unchanged?      (->> results (remove :no-consume) empty?)
            upstream?       (fn [[r _]]
                              (let [r-level (->> r (get rid-map) (get level-map))]
                                (or (nil? r-level) (< r-level level))))
            all-rvts        (mapcat :output-rvts results)
            _               (log-rvts "output" all-rvts)
            [compl-rvts
             value-rvts]    (dissect (fn [[_ [v _]]] (= v ::completed)) all-rvts)
            [upstream-rvts
             downstream-rvts] (dissect upstream? value-rvts)]
        ;; push value into next cycle if reactive level is either
        ;; unknown or is lower than current level
        (doseq [[r [v t]] upstream-rvts]
          (enq *netref* {:rvt-map {r [v t]}}))
        (-> n
            (dissoc :completed)
            (propagate-downstream pending-links downstream-rvts)
            (update-complete-sets nil (map first compl-rvts))
            (auto-complete)
            (remove-links (remove-links-pred n completed-rs results))
            (update-complete-sets (mapcat :dont-complete results)
                                  (mapcat :allow-complete results))
            (auto-complete)
            (add-links (mapcat :add results))
            (assoc :unchanged? unchanged?)))
      (-> n
          (dissoc :completed)
          (assoc :unchanged? true)
          (remove-links (remove-links-pred n completed-rs nil))
          (auto-complete)))))


(defn- propagate
  "Executes one propagation cycle. Returns the network."
  [n pending-links [pending-rs completed-rs]]
  (update-from-results n (eval-links n pending-links pending-rs completed-rs)))


(defn- propagate-downstream
  "Propagate values to reactives that are guaranteed to be downstream."
  [network pending-links downstream-rvts]
  (loop [n      network
         rvts   downstream-rvts
         plinks pending-links]
    (let [[rvt-map remaining-rvts] (reduce (fn [[rvm remaining] [r vt]]
                                             (if (rvm r)
                                               [rvm (conj remaining [r vt])]
                                               [(assoc rvm r vt) remaining]))
                                           [{} []]
                                           rvts)]
      (if (or (seq plinks) (seq rvt-map))
        (recur (propagate n plinks (deliver-values! rvt-map))
               remaining-rvts
               nil)
        n))))


(defn update-and-propagate
  "Updates network with the contents of the stimulus map,
  delivers any values and runs propagation cycles as long as link-functions
  return non-empty results. Returns the network."
  [network {:keys [exec results rvt-map]}]
  (mon/duration
   (monitors *netref*)
   'update-and-propagate
   (if exec
     (apply-exec network exec)
     (let [[pending-rs
            completed-rs] (deliver-values! rvt-map)]
       (loop [n (-> network
                    (update-from-results [0 nil results completed-rs])
                    (propagate (mapcat :add results) [pending-rs nil]))
              [pending-rs
               completed-rs] (pending-and-completed-reactives n)]
         (let [n (-> n
                     (propagate nil [pending-rs completed-rs])
                     (rebuild-if-necessary))
               progress? (not (:unchanged? n))
               [pending-rs
                completed-rs] (if progress?
                                (pending-and-completed-reactives n)
                                [nil nil])]
           (if (seq pending-rs)
             (recur n [pending-rs completed-rs])
             n)))))))


;; ---------------------------------------------------------------------------
;; Netref related functions


(defn push!
  "Delivers a value v to the reactive r and starts a propagation
  cycle. Returns the value v."
  ([r v]
     (push! *netref* r v))
  ([netref r v]
     (push! netref r v (now)))
  ([netref r v t]
     (enq netref {:rvt-map {r [v t]}})
     v))


(defn complete!
  "Delivers the ::completed value into a reactive r and notifies the
  complete-fn handler of all links which have r as their
  input. Updates the network according to results of
  handlers. Returns ::completed."
  ([r]
     (complete! *netref* r))
  ([netref r]
     (enq netref {:rvt-map {r [::completed (now)]}})
     ::completed))


(defn add-links!
  "Adds links to the network. Returns the network ref."
  [netref & links]
  (enq netref {:results [{:add links}]}))


(defn remove-links!
  "Removes links from the network. Returns the network ref."
  [netref pred]
  (enq netref {:results [{:remove-by pred}]}))


(defn on-error
  "Installs error-fn in the link that has as only output the reactive
  r."
  [netref r error-fn]
  (enq netref {:exec [replace-link-error-fn
                      (fn [l]
                        (= (link-outputs l) [r]))
                      error-fn]})
  r)


(defn reset-network!
  "Removes all links and clears any other data from the network. 
  Returns :reset."
  [netref]
  (enq netref {:exec [(fn [n]
                        (make-network (:id n) []))]})
  :reset)


(defn pp
  "Pretty print network in netref."
  ([]
     (pp *netref*))
  ([netref]
     (let [{:keys [links rid-map]} (network netref)]
       (println (str "Reactives\n" (s/join "\n" (->> rid-map
                                                     keys
                                                     (map str-react)))
                     "\nLinks\n" (s/join "\n" (map str-link links)))))))


(defn dot
  "Returns a GraphViz dot representation of the network as string."
  ([]
     (dot *netref*))
  ([netref]
     (let [r-style "shape=box, regular=1, style=filled, fillcolor=white"
           l-style "shape=oval, width=0.5, style=filled, fillcolor=grey"
           {:keys [id links rid-map]} (network netref)
           id-str (fn [x]
                    (if (reactive? x)
                      (str "\"r:" (:label x) "\"")
                      (str "\"l:" (:label x) "\"")))
           node-str (fn [style x]
                      (str (id-str x) " [label=\"" (:label x) "\", " style "];\n"))]
       (str "digraph " id " {\n"
            (->> (keys rid-map)
                 (map (partial node-str r-style))
                 sort
                 (apply str))
            (->> links
                 (map (partial node-str l-style))
                 sort
                 (apply str))
            (->> links
                 (mapcat (fn [l]
                           (concat (map vector (repeat l) (link-outputs l))
                                   (map vector (link-inputs l) (repeat l)))))
                 (map (fn [[f t]]
                        (str (id-str f) " -> " (id-str t) ";\n")))
                 sort
                 (apply str))
            "}\n"))))


(defmacro with-netref
  "Binds the given netref to the dynamic var *netref* and executes
  the expressions within that binding."
  [netref & exprs]
  `(binding [reactnet.core/*netref* ~netref]
     ~@exprs))


:ok

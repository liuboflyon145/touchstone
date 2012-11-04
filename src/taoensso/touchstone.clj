(ns taoensso.touchstone
  "Simple, Carmine-backed Multi-Armed Bandit (MAB) split-testing. Both more
  effective and more convenient than traditional A/B testing. Fire-and-forget!

  Redis keys:
    * touchstone:<test-name>:nviews -> hash, {form-name views-count}
    * touchstone:<test-name>:scores -> hash, {form-name score}
    * touchstone:<test-name>:selection:<mab-subject-id> -> string, form-name

  Ref. http://goo.gl/XPlP6 (UCB1 MAB algo)
       http://en.wikipedia.org/wiki/Multi-armed_bandit
       http://stevehanov.ca/blog/index.php?id=132"
  {:author "Peter Taoussanis"}
  (:require [taoensso.carmine          :as car]
            [taoensso.touchstone.utils :as utils]))

;;;; Config & bindings

(defonce config (atom {:carmine {:pool (car/make-conn-pool)
                                 :spec (car/make-conn-spec)}}))

(defn set-config! [[k & ks] val] (swap! config assoc-in (cons k ks) val))

(defmacro ^:private wcar "With Carmine..."
  [& body]
  `(let [{pool# :pool spec# :spec} (@config :carmine)]
     (car/with-conn pool# spec# ~@body)))

(def ^:private ^:dynamic *mab-subject-id* nil)

(defmacro with-test-subject
  "Executes body (e.g. handling of a Ring web request) within the context of a
  thread-local binding for test-subject id. Ids are used to make selected
  testing forms \"sticky\", presenting a consistent user experience to each
  test subject during a particular testing session (+/- 2hrs).

  When nil/unspecified, subject will not participate in split-testing (useful
  for staff/bot web requests, etc.)."
  [id & body] `(binding [*mab-subject-id* (str ~id)] ~@body))

;;;;

(def ^:private tkey "Prefixed Touchstone key"
  (memoize (car/make-keyfn "touchstone")))

(def ^:private ucb1-score
  "Use \"UCB1\" formula to score a named MAB test form for selection sorting.

  UCB1 MAB provides a number of nice properties including:
    * Fire-and-forget capability.
    * Fast and accurate convergence.
    * Resilience to confounding factors over time.
    * Support for test-form hot-swapping.
    * Support for multivariate testing.

  Formula motivation: we want frequency of exploration to be inversly
  proportional to our confidence in the superiority of the leading form. This
  implies confidence in both relevant sample sizes, as well as the statistical
  significance of the difference between observed form scores."
  (utils/memoize-ttl
   10000 ; 10 secs, for performance
   (fn [test-name form-name]
     (let [[nviews-map score]
           (wcar (car/hgetall* (tkey test-name "nviews"))
                 (car/hget     (tkey test-name "scores") (name form-name)))

           score      (or (car/as-double score) 0)
           nviews     (car/as-long (get nviews-map (name form-name) 0))
           nviews-sum (reduce + (map car/as-long (vals nviews-map)))]

       (if (or (zero? nviews) (zero? nviews-sum))
         1000 ;; Very high score (i.e. always select untested forms)
         (+ (/ score nviews)
            (Math/sqrt (/ 0.5 (Math/log nviews-sum) nviews))))))))

(declare mab-select*)

(defmacro mab-select
  "Defines a named MAB test that selects and evaluates one of the named testing
  forms using the \"UCB1\" selection algorithm.

  Returns default (first) form when *mab-subject-id* is nil/unspecified.

      (mab-select :my-test-1
                  :my-form-1 \"String 1\"
                  :my-form-2 (do (Thread/sleep 2000) \"String 2\"))

  Test forms may be added or removed at any time, but avoid changing forms once
  named."
  [test-name & [default-form-name & _ :as name-form-pairs]]
  `(mab-select* ~test-name ~default-form-name
                ;; Note that to prevent caching of form evaluation, we actually
                ;; DO want a fresh delay-map for each call
                (utils/delay-map ~@name-form-pairs)))

(defn- mab-select*
  [test-name default-form-name delayed-forms-map]
  (if-not *mab-subject-id*

    ;; Return default form and do nothing else
    (force (get delayed-forms-map default-form-name))

    (let [selection-tkey           (tkey test-name "selection" *mab-subject-id*)
          prior-selected-form-name (keyword (wcar (car/get selection-tkey)))

          try-select-form!
          (fn [form-name]
            (when-let [form (force (get delayed-forms-map form-name))]
              (wcar ; Refresh 2 hr selection stickiness, inc view counter
               (car/setex selection-tkey (* 2 60 60) (name form-name))
               (car/hincrby (tkey test-name "nviews") (name form-name) 1))
              form))]

      ;; Honour a recent, valid pre-existing selection (for consistent user
      ;; experience); otherwise choose form with highest ucb1-score
      (or (try-select-form! prior-selected-form-name)
          (try-select-form!
           (last (sort-by #(ucb1-score test-name %) (keys delayed-forms-map))))))))

(comment (mab-select :landing.buttons.sign-up
                     :sign-up  "Sign-up!"
                     :join     "Join!"
                     :join-now "Join now!"))

(defn mab-commit!
  "Records the occurrence of one or more events, each of which will contribute
  a specified value (positive or negative) to a named MAB test score.

      ;; On sign-up button click:
      (mab-commit! :landing.buttons.sign-up 1
                   :landing.title           1)

      ;; On buy button click:
      (mab-commit! :sale-price order-item-qty)

  There's great flexibility in this to model all kinds of single or
  multivariate test->event interactions. Any event can contribute to the
  score of any test, positively or negatively, to any extent.

  The statistics can get complicated so try keep things simple: resist the urge
  to get fancy with the spices."
  ([test-name value]
     (when *mab-subject-id*
       (if-let [selected-form-name
                (keyword (wcar (car/get (tkey test-name "selection"
                                              *mab-subject-id*))))]
         (wcar (car/hincrby (tkey test-name "scores") (name selected-form-name)
                            value)))))
  ([test-name value & name-value-pairs]
     (dorun (map (fn [[n v]] (mab-commit! n v))
                 (partition 2 (into [test-name value] name-value-pairs))))))

(comment (mab-commit! :landing.buttons.sign-up 1 :landing.title 1))

(defn pr-mab-results
  "Prints sorted MAB test results."
  ([test-name]
     (let [[nviews-map scores-map]
           (wcar (car/hgetall* (tkey test-name "nviews"))
                 (car/hgetall* (tkey test-name "scores")))

           nviews-sum (reduce + (map car/as-long   (vals nviews-map)))
           scores-sum (reduce + (map car/as-double (vals scores-map)))]

       (println "---")
       (println (str "MAB test " test-name " with " nviews-sum " total views and"
                     " a cumulative score of " scores-sum ":"))
       (println (->> (for [form-name (keys nviews-map)]
                       [(keyword form-name) (ucb1-score test-name form-name)])
                     (sort-by second)
                     reverse))))
  ([test-name & more] (dorun (map pr-mab-results (cons test-name more)))))

(comment (pr-mab-results :landing.buttons.sign-up :landing.title))

(comment
  (wcar (car/hgetall* (tkey :landing.buttons.sign-up "nviews")))

  (with-test-subject "user1403"
    (mab-select
     :landing.buttons.sign-up
     :red    "Red button"
     :blue   "Blue button"
     :green  "Green button"
     :yellow "Yellow button"))

  (with-test-subject "user1403"
    (mab-commit! :landing.buttons.sign-up 100)))
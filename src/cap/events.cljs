(ns cap.events
  (:require [ajax.core :as ajax]
            [cemerick.url :as url]
            [clojure.spec.alpha :as s]
            [re-frame.core :as rf]
            [re-frame.interceptor :refer [->interceptor
                                          assoc-effect
                                          get-effect
                                          get-coeffect]]
            [taoensso.timbre :as log]))

(s/def :cap/category (s/or :nil nil? :keyword keyword?))

(defmulti db-status :cap/status)

(defmethod db-status :ok [_]
  (s/keys :req [:cap/status
                :cap/category]))

(s/def :cap/error map?)
(s/def :cap/error-context string?)

(defmethod db-status :error [_]
  (s/keys :req [:cap/status
                :cap/error-context
                :cap/error-data]
          :opt [:cap/error-event]))

(s/def :cap/db (s/multi-spec db-status :cap/status))

(defn spec-interceptor
  [spec f]
  (->interceptor
   :id :spec
   :after (fn spec-validation
            [context]
            (let [event (get-coeffect context :event)
                  db (or (get-effect context :db)
                         (get-coeffect context :db))]
              (if (s/valid? spec db)
                context
                (->> (f db event (s/explain-data spec db))
                     (assoc-effect context :db)))))))

(defn handle-invalid-db
  [db event data]
  (merge db {:cap/status :error
             :cap/error-context (str " validating db after " (first event))
             :cap/error-event event
             :cap/error-data data}))

(def my-spec-interceptor (spec-interceptor :cap/db handle-invalid-db))

(def interceptors [my-spec-interceptor rf/debug])

(def token-url "http://localhost:8001/api/tokens")

(rf/reg-event-fx
 :fetch-token
 interceptors
 (fn [{db :db} _]
   {:http-xhrio {:method :post
                 :uri token-url
                 :params {:bottle/username "mike"
                          :bottle/password "rocket"}
                 :format (ajax/transit-request-format)
                 :response-format (ajax/raw-response-format)
                 :on-success [:token-success]
                 :on-failure [:token-failure]}}))

(rf/reg-event-db
 :token-success
 interceptors
 (fn [db [_ token]]
   (log/debug "Retrieved token:" token)
   (assoc db :cap/token token)))

(rf/reg-event-db
 :token-failure
 interceptors
 (fn [db [_ failure]]
   {:cap/status :error
    :cap/error-context "fetching token."
    :cap/error-data failure}))

(rf/reg-event-db
 :set-category
 interceptors
 (fn [db [_ category]]
   (assoc db :cap/category category)))

(rf/reg-event-fx
 :fetch-events
 interceptors
 (fn [{db :db} _]
   (let [category (:cap/category db)]
     {:db (assoc db :events [])})))

(rf/reg-event-fx
 :boot
 interceptors
 (fn [{db :db} _]
   (let [url (-> js/window .-location .-href url/url)
         category (keyword (get-in url [:query "category"]))]
     {:db {:cap/status :booting
           :cap/category (if-let [existing-category (:cap/category db)]
                           existing-category
                           category)}
      :async-flow {:first-dispatch [:fetch-token]
                   :rules [{:when :seen-any-of? :events [:token-failure] :halt? true}]}})))

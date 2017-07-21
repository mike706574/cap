(ns cap.events
  (:require [ajax.core :as ajax]
            [bottle.specs]
            [cap.effects]
            [cemerick.url :as url]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [re-frame.core :as rf]
            [re-frame.spec-interceptor :refer [spec-interceptor]]
            [taoensso.timbre :as log]))

;; Config

(def host "198.98.55.152:8001")
(def token-url (str "http://" host "/api/tokens"))
(def events-url (str "http://" host "/api/events"))
(def websocket-url (str "ws://" host "/api/websocket"))

;; Specs

(defmulti db-status :cap/status)


;; booting

(defmethod db-status :booting [_]
  (s/keys :req [:cap/status]))


;; ok

(s/def :cap/category (s/or :nil nil? :keyword keyword?))
(s/def :cap/events (s/coll-of :bottle/event))

(defmethod db-status :ok [_]
  (s/keys :req [:cap/status
                :cap/category
                :cap/events]))

;; error

(s/def :cap/error map?)
(s/def :cap/error-context string?)

(defmethod db-status :error [_]
  (s/keys :req [:cap/status
                :cap/error-context
                :cap/error-data]
          :opt [:cap/error-event]))

;; db

(s/def :cap/db (s/multi-spec db-status :cap/status))

;; Utility

(defn fail
  ([context data]
   {:cap/status :error
    :cap/error-context context
    :cap/error-data data})
  ([context data db]
   (assoc (fail context data) :cap/db db)))

;; Event Handlers

(defn handle-invalid-db
  [db event data]
  (if (= (:cap/status db) :error)
    db
    (fail (str "Validating db after " (first event) ".") (assoc data :db db))))


(def custom-spec-interceptor
  (spec-interceptor :cap/db handle-invalid-db))
(def interceptors [rf/debug custom-spec-interceptor rf/trim-v])

(defn reg-event-db [k f] (rf/reg-event-db k interceptors f))
(defn reg-event-fx [k f] (rf/reg-event-fx k interceptors f))

;; Fetching token
(reg-event-fx
 :fetch-token
 (fn [{db :db} _]
   {:http-xhrio {:method :post
                 :uri token-url
                 :params {:bottle/username "mike"
                          :bottle/password "rocket"}
                 :headers {"Accept" "text/plain"}
                 :format (ajax/transit-request-format)
                 :response-format (ajax/raw-response-format)
                 :on-success [:token-success]
                 :on-failure [:token-failure]}}))

(reg-event-db
 :token-success
 (fn [db [token]]
   (log/debug "Retrieved token:" token)
   (assoc db :cap/token token)))

(reg-event-db
 :token-failure
 (fn [db [failure]]
   (fail "Fetching token." failure)))

;; Fetching events

(reg-event-fx
 :fetch-events
 (fn [{db :db} _]
   (let [token (:cap/token db)
         category (:cap/category db)]
     {:http-xhrio {:method :get
                   :uri events-url
                   :headers {"Authorization" (str "Token " token)}
                   :response-format (ajax/transit-response-format)
                   :on-success [:events-success]
                   :on-failure [:events-failure]}})))

(reg-event-db
 :events-success
 (fn [db [events]]
   (log/debug "Retrieved events:" events)
   (assoc db :cap/events events)))

(reg-event-db
 :events-failure
 (fn [db [failure]]
   (fail "Fetching events." failure)))

;; Create event

(reg-event-fx
 :create-event
 (fn [{db :db} [category]]
   (let [token (:cap/token db)]
     {:http-xhrio {:method :post
                   :uri events-url
                   :headers {"Authorization" (str "Token " token)}
                   :params {:bottle/category category}
                   :format (ajax/transit-request-format)
                   :response-format (ajax/transit-response-format)
                   :on-success [:manual-event-created]
                   :on-failure [:manual-event-creation-failure]}})))

(reg-event-db
 :manual-event-created
 (fn [db [_]]
   (log/debug "Created manual event.")
   db))

(reg-event-db
 :manual-event-creation-failed
 (fn [db [failure]]
   (fail "Failed to create manual event." failure)))


;; Websocket
;; TODO: Use boomerang.
(defn decode
  [message]
  (transit/read (transit/reader :json) message))

(defn pretty [form] (with-out-str (cljs.pprint/pprint form)))

(reg-event-db
 :mesage-received
 (fn [db [message-event]]
   (let [message-data (.-data message-event)
         {:as message
          :keys [:bottle/message-type :bottle/event]} (decode message-data)]
     (log/debug "Message received:\n" (pretty message))
     (case message-type
       "created" (update db :cap/events conj event)
       "closed" db ;; TODO: Update.
       ))))

(reg-event-fx
 :connect-websocket
 (fn [{db :db} _]
   {:websocket {:method :get
                :uri websocket-url
                :on-message [:event-created]
                :on-success [:websocket-success]
                :on-failure [:websocket-failure]}}))

(reg-event-db
 :websocket-success
 (fn [db [socket]]
   (log/debug "Connected.")
   (assoc db :cap/websocket socket)))

(reg-event-db
 :websocket-failure
 (fn [db [failure]]
   (fail "Connecting websocket." {:message (str "Failed to connect to " (-> failure .-target .-url) ".")})))

(reg-event-db
 :websocket-error
 (fn [db [failure]]
   (fail "Websocket connection.." failure)))

;; UI

(reg-event-db
 :set-category
 (fn [db [category]]
   (assoc db :cap/category category)))

;; Life cycle
(reg-event-fx
 :boot
 (fn [{db :db} _]
   (when-let [websocket (:cap/websocket db)]
     (log/info "Closing existing websocket.")
     (.close websocket))
   (let [url (-> js/window .-location .-href url/url)
         category (keyword (get-in url [:query "category"]))]
     {:db {:cap/status :booting
           :cap/category (if-let [existing-category (:cap/category db)]
                           existing-category
                           category)}
      :async-flow {:first-dispatch [:fetch-token]
                   :rules [{:when :seen? :events :token-success :dispatch-n [[:fetch-events] [:connect-websocket]]}
                           {:when :seen-all-of? :events [:events-success :websocket-success] :dispatch [:start]}
                           {:when :seen? :events :start :halt? true}
                           {:when :seen-any-of? :events [:token-failure
                                                         :events-failure
                                                         :websocket-failure] :halt? true}]}})))

(reg-event-db
 :start
 (fn [db _]
   (assoc db :cap/status :ok)))

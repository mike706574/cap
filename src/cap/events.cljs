(ns cap.events
  (:require [ajax.core :as ajax]
            [bottle.specs]
            [cemerick.url :as url]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [re-frame.core :as rf]
            [re-frame.spec-interceptor :refer [spec-interceptor]]
            [taoensso.timbre :as log]))

;; Config

(def token-url "http://localhost:8001/api/tokens")
(def events-url "http://localhost:8001/api/events")
(def websocket-url "ws://localhost:8001/api/websocket")

;; Effects

(defn websocket-effect
  [{:as request
    :keys [uri on-message on-error on-success on-failure]}]
  (let [socket (js/WebSocket. uri)]
    (set! (.-onmessage socket) #(rf/dispatch (conj on-message %)))
    (set! (.-onerror socket) #(rf/dispatch (conj on-failure %)))
    (set! (.-onopen socket) (fn on-open []
                              (set! (.-onerror socket) #(rf/dispatch (conj on-error %)))
                              (rf/dispatch (conj on-success socket))))))

(rf/reg-fx :web-socket websocket-effect)

;; Specs

(defmulti db-status :cap/status)

;; booting

(defmethod db-status :booting [_]
  (s/keys :req [:cap/status]))

;; ok

(s/def :cap/category (s/or :nil nil? :keyword keyword?))
(s/def :cap/events (s/map-of :bottle/id :bottle/event))

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

(defn fail [context data]
  {:cap/status :error
   :cap/error-context context
   :cap/error-data data})

;; Event Handlers

(defn handle-invalid-db
  [db event data]
  (fail (str "Validating db after " (first event) ".") data))

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

;; Fetching events

(reg-event-fx
 :fetch-events
 (fn [{db :db} _]
   (let [token (:cap/token db)
         category (:cap/category db)]
     {:http-xhrio {:method :get
                   :uri events-url
                   :headers {"Authorization" (str "Token " token)}
                   :format (ajax/transit-request-format)
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

;; Websocket
;; TODO: Use boomerang.
(defn decode
  [message]
  (transit/read (transit/reader :json) message))

(reg-event-db
 :event-created
 (fn [db [message-event]]
   (let [message (.-data message-event)
         _ (println message)
         event (decode message)]
     (println "Event created:" event)
     (update db :cap/events conj event))))

(reg-event-fx
 :connect-websocket
 (fn [{db :db} _]
   (let [token (:cap/token db)
         category (:cap/category db)]
     {:web-socket {:method :get
                   :uri websocket-url
                   :on-message [:event-created]
                   :on-success [:websocket-success]
                   :on-failure [:websocket-failure]}})))

(reg-event-db
 :websocket-success
 (fn [db [socket]]
   (log/debug "Connected.")
   (assoc db :cap/websocket socket)
   db))

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

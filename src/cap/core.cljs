(ns cap.core
  (:require [ajax.core :as ajax]
            [clojure.string :as str]
            [day8.re-frame.http-fx]
            [reagent.core :as reagent]
            [re-frame.core :as rf]))

;; -- Development --------------------------------------------------------------
(enable-console-print!)

;; -- Event Dispatch -----------------------------------------------------------


;; -- Event Handlers -----------------------------------------------------------

(rf/reg-event-fx
 :initialize
 (fn [_ _]
   {:db {:name "Bob"}}))


;; -- Query  -------------------------------------------------------------------

(rf/reg-sub
 :name
 (fn [db _]
   (:name db)))

;; -- View Functions -----------------------------------------------------------

(defn app
  []
  [:div
   [:p "Hello, " @(rf/subscribe [:name]) "!"]])

;; -- Entry Point -------------------------------------------------------------

(defn ^:export run
  []
  (rf/dispatch-sync [:initialize])
  (reagent/render [app] (js/document.getElementById "app")))

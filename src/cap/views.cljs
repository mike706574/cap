(ns cap.views
  (:require [reagent.core :as r]
            [re-frame.core :as rf]))

(defn select
  [options on-change]
  (let [selected-value (r/atom (first (:value options)))]
    (fn []
      [:select {:value selected-value
                :on-change #(let [new-value (.. % -target -value)]
                              (reset! selected-value new-value)
                              (on-change new-value))}
       (for [{:keys [value label]} options]
         [:option {:key value
                   :value value} label])])))

(defn text-submit
  [on-submit]
  (let [value (r/atom "")]
    (fn []
      [:form
       [:input {:type "text"
                :value @value
                :on-change  #(let [new-value (.. % -target -value)]
                               (reset! value new-value))}]
       [:input {:type "submit"
                :value "Submit"
                :on-click #(do (on-submit @value)
                               (.preventDefault %))}]])))

(defn pretty [form] (with-out-str (cljs.pprint/pprint form)))

(defn ok []
  (let [events @(rf/subscribe [:events])]
    [:div
     [text-submit #(rf/dispatch [:create-event (keyword %)])]
     (if (empty? events)
       [:p "No events."]
       [:ul
        (for [event events]
          [:li {:key (:bottle/id event)} [:p (pretty event)]])])]))

(defn error []
  (let [error @(rf/subscribe [:error])]
    [:div.error
     [:h1 "Error: " (:cap/error-context error)]
     [:pre (pretty (:cap/error-data error))]
     (when-let [db (:cap/db error)]
       [:div
        [:h5 "Database:"]
        [:pre (pretty db)]])]))

(defn app []
  (let [status @(rf/subscribe [:status])]
    (case status
      :ok [ok]
      :booting [:p "Booting..."]
      :error [error]
      [:p "Error!"])))

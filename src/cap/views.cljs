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

(defn pretty [form] (with-out-str (cljs.pprint/pprint form)))

(defn ok []
  (let [events @(rf/subscribe [:events])]
    (if (empty? events)
      [:p "No events."]
      [:ul
       (for [event events]
         [:li {:key (str event)} [:p (pretty event)]])])))

(defn error []
  (let [error @(rf/subscribe [:error])]
    [:div.error
     [:h1 "Error: " (:cap/error-context error)]
     [:pre (pretty (:cap/error-data error))]]))

(defn app []
  (let [status @(rf/subscribe [:status])]
    (case status
      :ok [ok]
      :booting [:p "Booting..."]
      :error [error]
      [:p "Error!"])))

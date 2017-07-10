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

(defn error []
  (let [error @(rf/subscribe [:error])]
    (println "ERR:" error)
    [:div
     [:h1 "Error " (:cap/error-context error)]
     (when-let [event (:cap/error-event error)]
       [:event
        [:h5 "Event"]
        [:pre (pretty event)]])
     [:h5 "Data"]
     [:pre (pretty (:cap/error-data error))]]))

(defn app []
  (let [status @(rf/subscribe [:status])]
    (println "STATUS: " status)
    (case status
      :ok [:p "Ok!"]
      :booting [:p "Booting..."]
      :error [error]
      [:p "Error!"])))

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

(defn error []
  [:div
   [:h1 "Error " @(rf/subscribe [:error-context])]
   [:pre (with-out-str (cljs.pprint/pprint @(rf/subscribe [:error-data])))]])

(defn app []
  (let [status @(rf/subscribe [:status])]
    (println "STATUS: " status)
    (case status
        :ok [:p "Ok!"]
        :booting [:p "Booting..."]
        :error [error]
        [:p "Error!"])))

(ns cap.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub
 :status
 (fn [db _]
   (:cap/status db)))

(rf/reg-sub
 :error
 (fn [db _]
   (select-keys db [:cap/error-context
                    :cap/error-data
                    :cap/error-event])))

(rf/reg-sub
 :category
 (fn [db _]
   (:cap/category db)))

(rf/reg-sub
 :events
 (fn [db _]
   (:cap/events db)))

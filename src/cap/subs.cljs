(ns cap.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub
 :status
 (fn [db _]
   (:cap/status db)))

(rf/reg-sub
 :error-data
 (fn [db _]
   (:cap/error db)))

(rf/reg-sub
 :error-context
 (fn [db _]
   (:cap/context db)))

(rf/reg-sub
 :category
 (fn [db _]
   (:cap/category db)))

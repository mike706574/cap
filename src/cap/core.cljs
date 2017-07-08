(ns cap.core
  (:require [cap.events]
            [cap.subs]
            [cap.views]
            [day8.re-frame.async-flow-fx]
            [day8.re-frame.http-fx]
            [devtools.core :as devtools]
            [goog.events :as events]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [taoensso.timbre :as log]))

(devtools/install!)
(enable-console-print!)
(log/set-level! :debug)

(defn ^:export run
  []
  (rf/dispatch-sync [:boot])
  (r/render [cap.views/app] (js/document.getElementById "app")))

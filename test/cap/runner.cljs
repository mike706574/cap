(ns cap.runner
    (:require [doo.runner :refer-macros [doo-tests]]
              [cap.core-test]))

(doo-tests 'cap.core-test)

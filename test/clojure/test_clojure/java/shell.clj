;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.test-clojure.java.shell
  (:use clojure.test
	[clojure.java.shell :as sh])
  (:import (java.io File)))

(def platform-enc (.name (java.nio.charset.Charset/defaultCharset)))

(deftest test-parse-args
  (are [x y] (= x y)
       [[] {:inenc platform-enc :outenc platform-enc :dir nil :env nil}] (#'sh/parse-args [])
       [["ls"] {:inenc platform-enc :outenc platform-enc :dir nil :env nil}] (#'sh/parse-args ["ls"])
       [["ls" "-l"] {:inenc platform-enc :outenc platform-enc :dir nil :env nil}] (#'sh/parse-args ["ls" "-l"])
       [["ls"] {:inenc platform-enc :outenc "ISO-8859-1" :dir nil :env nil}] (#'sh/parse-args ["ls" :outenc "ISO-8859-1"])))
  
(deftest test-with-sh-dir
  (are [x y] (= x y)
    nil *sh-dir*
    "foo" (with-sh-dir "foo" *sh-dir*)))

(deftest test-with-sh-env
  (are [x y] (= x y)
    nil *sh-env*
    {:KEY "VAL"} (with-sh-env {:KEY "VAL"} *sh-env*)))

(deftest test-as-env-strings
  (are [x y] (= x y)
    nil (#'sh/as-env-strings nil)
    ["FOO=BAR"] (seq (#'sh/as-env-strings {"FOO" "BAR"}))
    ["FOO_SYMBOL=BAR"] (seq (#'sh/as-env-strings {'FOO_SYMBOL "BAR"}))
    ["FOO_KEYWORD=BAR"] (seq (#'sh/as-env-strings {:FOO_KEYWORD "BAR"}))))


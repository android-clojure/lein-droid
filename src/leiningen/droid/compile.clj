;; Generally **lein-droid** tries to reuse as much Leiningen code as
;; possible. Compilation subtasks in this namespace just call their
;; Leiningen counterparts.
;;
(ns leiningen.droid.compile
  "This part of the plugin is responsible for the project compilation."
  (:refer-clojure :exclude [compile])
  (:require leiningen.compile leiningen.javac
            [clojure.java.io :as io]
            [leiningen.core.eval :as eval])
  (:use [leiningen.droid.utils :only [get-sdk-android-jar unique-jars
                                      ensure-paths]]
        [leiningen.core
         [main :only [debug info abort]]
         [classpath :only [get-classpath]]]
        [robert.hooke :only [add-hook]]
        [bultitude.core :only [namespaces-on-classpath]]))

;; Before defining actual `compile` functions we have to manually
;; attach Android SDK libraries to the classpath. The reason for this
;; is that Leiningen doesn't handle external dependencies at the high
;; level, that's why we hack `get-classpath` function.

(defn classpath-hook
  "Takes the original `get-classpath` function and the project map,
  extracting the path to the Android SDK and the target version from it.
  Then the path to the actual `android.jar` file is constructed and
  appended to the rest of the classpath list. Also removes all duplicate
  jars from the classpath."
  [f {{:keys [sdk-path target-version]} :android :as project}]
  (let [classpath (f project)
        [jars paths] ((juxt filter remove) #(re-matches #".+\.jar" %) classpath)
        result (conj (concat (unique-jars jars) paths)
                     (get-sdk-android-jar sdk-path target-version)
                     (str sdk-path "/tools/support/annotations.jar"))]
    (debug result)
    result))

(add-hook #'leiningen.core.classpath/get-classpath #'classpath-hook)

(defn compile-clojure
  "Taken partly from Leiningen source code.

  Compiles Clojure files into .class files.

  If `:aot` project parameter equals `:all` then compiles the
  necessary dependencies. If `:aot` equals `:all-with-unused` then
  compiles all namespaces of the dependencies whether they were
  referenced in the code or not. The latter is useful for the
  development."
  [{:keys [aot aot-exclude-ns] :as project}]
  (if (= aot :all-with-unused)
    (let [nses (namespaces-on-classpath :classpath
                                        (map io/file (get-classpath project)))
          nses (remove (set (map symbol aot-exclude-ns)) nses)]
      (try
        (let [form `(doseq [namespace# '~nses]
                      (println "Compiling" namespace#)
                      (clojure.core/compile namespace#))
              project (update-in project [:prep-tasks]
                                 (partial remove #{"compile"}))]
          (.mkdirs (io/file (:compile-path project)))
          (try (eval/eval-in-project project form)
               (info "Compilation succeeded.")
               (catch Exception e
                 (abort "Compilation failed.")))))
      (info "All namespaces already :aot compiled."))
    (leiningen.compile/compile project)))

(defn compile
  "Compiles both Java and Clojure source files."
  [{{:keys [sdk-path]} :android :as project} & args]
  (ensure-paths sdk-path)
  (apply leiningen.javac/javac project args)
  (compile-clojure project))
(ns leiningen.droid.build
  "A set of functions and subtasks responsible for building the
  Android project."
  (:refer-clojure :exclude [compile])
  (:use [leiningen.core
         [classpath :only [resolve-dependencies]]
         [main :only [debug info]]]
        [leiningen.droid
         [compile :only [compile]]
         [utils :only [get-sdk-android-jar unique-jars first-matched proj sh
                       dev-build? ensure-paths with-process]]
         [manifest :only [write-manifest-with-internet-permission]]])
  (:require [clojure.java.io :as io]))

;; ### Helper functions

(defn- append-suffix
  "Appends a suffix to a filename, e.g. transforming `sample.apk` into
  `sample-signed.apk`"
  [filename suffix]
  (let [[_ without-ext ext] (re-find #"(.+)(\.\w+)" filename)]
    (str without-ext "-" suffix ext)))

;; ### Subtasks

;; Since the execution of `dx` takes a pretty lot of time we need to
;; ensure that its subprocess will be killed if user cancels the build
;; (sends SIGINT to leiningen). That is why we add a hook to the
;; runtime that will be triggered when Leiningen is closed.
;;
(defn create-dex
  "Creates the DEX file from the compiled .class files. It is done by
  executing `dx` binary from Android SDK."
  [{{:keys [sdk-path out-dex-path]} :android,
    compile-path :compile-path :as project}]
  (info "Creating dex...")
  (ensure-paths sdk-path)
  (let [dx-bin (str sdk-path "/platform-tools/dx")
        annotations (str sdk-path "/tools/support/annotations.jar")
        deps (unique-jars (resolve-dependencies :dependencies project))]
    (with-process [proc (map str
                             (flatten '(dx-bin "--dex" "--output" out-dex-path
                                               compile-path annotations deps)))]
      (.addShutdownHook (Runtime/getRuntime) (Thread. #(.destroy proc))))))

(defn build
  "Compiles all source files and creates a DEX-file."
  [project]
  (doto project compile create-dex))

(defn crunch-resources
  "Calls `aapt` binary with the _crunch_ task."
  [{{:keys [sdk-path res-path out-res-path]} :android}]
  (info "Crunching resources...")
  (ensure-paths sdk-path res-path)
  (let [aapt-bin (str sdk-path "/platform-tools/aapt")]
    (sh aapt-bin "crunch -v"
        "-S" res-path
        "-C" out-res-path)))

(defn package-resources
  "Calls `aapt` binary with the _package_ task.

  If this task in run with :dev profile, then it ensures that
  AndroidManifest.xml has Internet permission for running REPL. This
  is achieved by backing up the original manifest file and creating a
  new one with Internet permission appended to it. After the packaging
  the original manifest file is restored."
  [{{:keys [sdk-path target-version manifest-path assets-path res-path
            out-res-path out-res-pkg-path]} :android :as project}]
  (info "Packaging resources...")
  (ensure-paths sdk-path manifest-path res-path)
  (let [aapt-bin (str sdk-path "/platform-tools/aapt")
        android-jar (get-sdk-android-jar sdk-path target-version)
        dev-build (dev-build? project)
        manifest-file (io/file manifest-path)
        backup-file (io/file (str manifest-path ".backup"))
        ;; Only add `assets` directory if it is present.
        assets (if (.exists (io/file assets-path)) ["-A" assets-path] [])]
    (when dev-build
      (io/copy manifest-file backup-file)
      (write-manifest-with-internet-permission manifest-path))
    (sh aapt-bin "package" "--no-crunch" "-f" "--debug-mode"
        "-M" manifest-path
        "-S" out-res-path
        "-S" res-path assets
        "-I" android-jar
        "-F" out-res-pkg-path
        "--generate-dependencies")
    (when dev-build
      (io/copy backup-file manifest-file)
      (io/delete-file backup-file))))

(defn create-apk
  "Creates an APK file by running `apkbuilder` tool on the generated
  DEX-file and resource package. The output APK file has a
  _-debug-unaligned_ suffix."
  [{{:keys [sdk-path out-apk-path out-res-pkg-path out-dex-path]} :android,
    source-paths :source-paths, java-source-paths :java-source-paths
    :as project}]
  (info "Creating APK...")
  (ensure-paths sdk-path out-res-pkg-path out-dex-path)
  (let [apkbuilder-bin (str sdk-path "/tools/apkbuilder")
        unaligned-path (append-suffix out-apk-path "debug-unaligned")
        source-paths-args (mapcat #(vector "-rf" %) (concat source-paths
                                                            java-source-paths))]
    (sh apkbuilder-bin unaligned-path "-u"
        "-z" out-res-pkg-path
        "-f" out-dex-path
        source-paths-args)))

(defn sign-apk
  "Signs APK file with a key from the debug keystore."
  [{{:keys [out-apk-path keystore-path]} :android}]
  (info "Signing APK...")
  (let [unaligned-path (append-suffix out-apk-path "debug-unaligned")]
    (ensure-paths unaligned-path keystore-path)
    (sh "jarsigner"
        "-keystore" keystore-path
        "-storepass" "android"
        "-keypass" "android"
        unaligned-path "androiddebugkey")))

(defn zipalign-apk
  "Calls `zipalign` binary on APK file. The output APK file has
  _-debug_ suffix."
  [{{:keys [sdk-path out-apk-path]} :android}]
  (info "Aligning APK...")
  (let [zipalign-bin (str sdk-path "/tools/zipalign")
        unaligned-path (append-suffix out-apk-path "debug-unaligned")
        aligned-path (append-suffix out-apk-path "debug")]
    (ensure-paths sdk-path unaligned-path)
    (sh zipalign-bin "4" unaligned-path aligned-path)))

(defn apk
  "Crunches and packages resources, creates an APK file, signs and zip-aligns it."
  [project]
  (doto project
    crunch-resources package-resources
    create-apk sign-apk zipalign-apk))

(defn install
  "Installs the current debug APK to the connected device."
  [{{:keys [sdk-path out-apk-path]} :android}]
  (info "Installing APK...")
  (let [adb-bin (str sdk-path "/platform-tools/adb")
        apk-debug-path (append-suffix out-apk-path "debug")]
    (ensure-paths sdk-path apk-debug-path)
    (sh adb-bin "-d" "install" "-r" apk-debug-path)))

(comment
  (create-dex (leiningen.droid.utils/proj))
  (crunch-resources (leiningen.droid.utils/proj))
  (package-resources (leiningen.droid.utils/proj))
  (create-apk (leiningen.droid.utils/proj))
  (sign-apk (leiningen.droid.utils/proj))
  (zipalign-apk (leiningen.droid.utils/proj))
  (install (leiningen.droid.utils/proj))
  )
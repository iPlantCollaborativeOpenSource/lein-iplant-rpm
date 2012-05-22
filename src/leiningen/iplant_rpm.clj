(ns leiningen.iplant-rpm
  (:use [clojure.java.io :only [file copy reader]]
        [clojure.string :only [join split]]
        [fleet])
  (:import [java.io FilenameFilter]))

;; Templates to use for various project types.
(def ^{:private true} templates-by-type
  {:service {:spec "rpm/svc-spec.fleet"
             :exec "rpm/svc-init.fleet"}
   :command {:spec "rpm/cmd-spec.fleet"
             :exec "rpm/cmd-exec.fleet"}})

;; The path to various RPM directories.
(def ^{:private true} rpm-base-dir (file "/usr/src/redhat"))
(def ^{:private true} rpm-spec-dir (file rpm-base-dir "SPECS"))
(def ^{:private true} rpm-source-dir (file rpm-base-dir "SOURCES"))
(def ^{:private true} rpm-build-dir (file rpm-base-dir "BUILD"))
(def ^{:private true} rpm-dir (file rpm-base-dir "RPMS/noarch"))

(defn- pump
  "Pumps data obtained from a reader to an output stream.  Copied shamelessly
   from leiningen.core.eval/pump."
  [reader out]
  (let [buffer (char-array 1024)]
    (loop [len (.read reader buffer)]
      (when-not (neg? len)
        (.write out buffer 0 len)
        (.flush out)
        (Thread/sleep 100)
        (recur (.read reader buffer))))))

(defn- sh
  "A version of clojure.java.shell/sh that streams out/err.  Copied shamelessly
   from leiningen.core.eval/sh.  This version of (sh) is being used because
   clojure.java.shell/sh wasn't calling .destroy on the process, which was
   preventing this program from exiting in a timely manner.  It's also
   convenient to be able to stream standard output and standard error output to
   the user's terminal session.  I decided to copy the code to this location so
   that this plugin will be compatible with both lein1 and lein2."
  [& cmd]
  (let [proc (.exec (Runtime/getRuntime) (into-array cmd))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (.destroy proc))))
    (with-open [out (reader (.getInputStream proc))
                err (reader (.getErrorStream proc))]
      (let [pump-out (doto (Thread. #(pump out *out*)) .start)
            pump-err (doto (Thread. #(pump err *err*)) .start)]
        (.join pump-out)
        (.join pump-err))
      (.waitFor proc))))

(defn- inform
  "Prints an informational message to standard output."
  [& ms]
  (println (join " " ms))
  (flush))

(defn- warn
  "Prints a warning message to standard error output."
  [& ms]
  (binding [*out* *err*]
    (println (join " " ms))
    (flush)))

(defn- template-for
  "Returns the template for the given project type and template type."
  [project-type template-type]
  (get-in templates-by-type [project-type template-type]))

(defn- slurp-resource
  "Slurps the contents of a resource that can be found relative to a location
   on the classpath."
  [resource-path]
  (let [loader (.. (Thread/currentThread) getContextClassLoader)]
    (slurp (.getResourceAsStream loader resource-path))))

(defn- load-template
  "Loads a Fleet template from a template file that is located relative to a
   location on the classpath."
  [template-path]
  (fleet [spec] (slurp-resource template-path) {:escaping :bypass}))

(defn- project-to-settings
  "Converts a project map to the settings map that we need to fill in the
   templates."
  [project]
  (let [settings (:iplant-rpm project {})]
    (assoc settings
           :summary (:summary settings "")
           :name (:name project)
           :version (first (split (:version project) #"-"))
           :release (:release settings 1)
           :provides (:provides settings (:name project))
           :type (:type settings :service)
           :dependencies (:dependencies settings [])
           :description (:description project "")
           :jar-version (:version project)
           :config-files (:config-files settings [])
           :config-path (:config-path settings)
           :exe-files (:exe-files settings [])
           :resources (:resources settings [])
           :extra-classpath-dirs (:extra-classpath-dirs project []))))

(defn- validate-settings
  "Verifies that this plugin can process the project settings."
  [settings]
  (when (nil? (get templates-by-type (:type settings)))
    (throw (Exception. (str "unknown project type: " (:type settings)))))
  (when (and (seq? (:config-files settings)) (nil? (:config-path settings)))
    (throw (Exception. (str "config-path is required in projects with "
                            "configuration files")))))

(defn- build-and-validate-settings
  "Builds and validates the settings map for this plugin."
  [project]
  (doto (project-to-settings project)
    validate-settings))

(defn- gen-file
  "Generates a file with the given name using the given template name."
  [settings file-name template-name]
  (spit file-name (str ((load-template template-name) settings))))

(defn- mkdirs
  "Creates a directory and any parent directories that need to be created.  If
   the directory already exists then this function is a no-op."
  [dir]
  (let [f (file dir)]
    (if (.exists f)
      (when (not (.isDirectory f))
        (throw (Exception. (str dir " exists and is not a directory"))))
      (when (not (.mkdirs f))
        (throw (Exception. (str "unable to create " dir)))))))

(declare rec-copy)

(defn- copy-dir
  "Copies the contents of a directory to another directory."
  [dest f]
  (mkdirs dest)
  (rec-copy dest (seq (.listFiles f))))

(defn- copy-file-or-dir
  "Copies either a file or a directory."
  [dir f]
  (let [dest (file dir (.getName f))]
    (cond (.isFile f) (copy f dest)
          (.isDirectory f) (copy-dir dest f)
          :else (throw (Exception. "unrecognized file type")))))

(defn- rec-copy
  "Performs a recursive copy of one or more files.  Note that recursion does
   consume stack space.  This shouldn't be a problem, however, because a
   directory structure that is deep enough to cause a stack overflow will
   probably create a path that is too long for the OS to support."
  [dir fs]
  (dorun (map #(copy-file-or-dir dir %) fs)))

(defn- copy-dir-structure
  "Copies files or directories to a destination directory, preserving the
   relative paths of the source files or directories."
  [dir fs]
  (dorun (map #(let [dest-dir (.getParentFile (file dir %))]
                 (mkdirs (.getPath dest-dir))
                 (rec-copy dest-dir [(file %)]))
              (filter #(not (nil? %)) fs))))

(defn- rec-delete
  "Recursively deletes all files in a directory structure rooted at the given
   directory.  Note that this recursion does consume stack space.  This
   shouldn't be a problem, however, because a directory structure that is deep
   enough to cause a stack overflow will probably create a path that is too
   long for the OS to support."
  [f]
  (when (.isDirectory f)
    (dorun (map #(rec-delete %) (.listFiles f))))
  (.delete f))

(defn- build-spec-file
  "Builds the RPM specification file."
  [settings]
  (let [spec-name (str (:name settings) ".spec")]
    (gen-file settings spec-name (template-for (:type settings) :spec))
    spec-name))

(defn- make-build-dir
  "Creates the build directory, which will be used to generate the source
   tarball."
  [build-dir settings exec-name]
  (let [config-dir (file (:config-path settings))
        extra-classpath-dirs (:extra-classpath-dirs settings)
        exe-files (:exe-files settings)]
    (mkdirs build-dir)
    (rec-copy build-dir (map #(file %) [exec-name "project.clj" "src"]))
    (copy-dir-structure build-dir (conj extra-classpath-dirs config-dir))
    (copy-dir-structure build-dir exe-files)))

(defn- exec
  "Executes a command, throwing an exception if the command fails."
  [& args]
  (let [status (apply sh args)]
    (when (not= status 0)
      (let [cmd (join " " args)]
        (throw (Exception. (str cmd " failed with status " status)))))))

(defn- build-source-tarball
  "Builds the source tarball that will be used by rpmbuild to generate the
   RPM and returns the base name of the generated tarball, which is needed
   for cleanup work."
  [settings]
  (let [build-dir (file (str (:provides settings) "-" (:version settings)))
        tarball-name (str build-dir ".tar.gz")
        exec-name (:name settings)]
    (inform "Building the source tarball...")
    (gen-file settings exec-name (template-for (:type settings) :exec))
    (make-build-dir build-dir settings exec-name)
    (exec "tar" "czvf" tarball-name (.getPath build-dir))
    (rec-delete build-dir)
    [build-dir tarball-name]))

(defn- delete-existing-files
  "Deletes existing files in the given directory with the given extension."
  [dir ext]
  (let [filt (proxy [FilenameFilter] []
               (accept [dir filename]
                       (.endsWith filename ext)))]
    (dorun (map #(.delete %) (.listFiles dir filt)))))

(defn- move
  "Moves a file to a new location or file name."
  [src dest]
  (copy src dest)
  (.delete src))

(defn- clean-up-old-files
  "Cleans up any files that may be left over from previous builds."
  []
  (inform "Cleaning up files from previous builds...")
  (let [working-dir (file (System/getProperty "user.dir"))]
    (delete-existing-files working-dir ".rpm")
    (delete-existing-files working-dir ".tar.gz")))

(defn- build-rpm
  "Builds the RPM."
  [prj]
  (clean-up-old-files)
  (let [settings (build-and-validate-settings prj)
        [source-dir-name tarball-name] (build-source-tarball settings)
        tarball-file (file tarball-name)
        tarball-path (file rpm-source-dir tarball-name)
        spec-file (file (build-spec-file settings))
        spec-dest (file rpm-spec-dir spec-file)
        release (:release settings)
        rpm-file (file (str source-dir-name "-" release ".noarch.rpm"))
        working-dir (file (System/getProperty "user.dir"))]
    (inform "Staging files for rpmbuild...")
    (copy spec-file spec-dest)
    (move tarball-file tarball-path)
    (inform "Running rpmbuild...")
    (exec "rpmbuild" "-ba" (.getPath spec-dest))
    (inform "Getting generated RPMs and cleaning up...")
    (move (file rpm-dir rpm-file) (file working-dir rpm-file))
    (rec-delete (file rpm-build-dir source-dir-name))))

(defn iplant-rpm
  "Generates the type of RPM that is used by the iPlant Collaborative to
   distribute web services written in Clojure."
  [project]
  (try
    (do (build-rpm project) 0)
    (catch Exception e
      (.printStackTrace e *err*)
      (flush)
      1)))

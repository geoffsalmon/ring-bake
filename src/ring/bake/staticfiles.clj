(ns ring.bake.staticfiles
  "Utility for syncing the contents of one directory with
another. Only copies files if the modification time is newer."
  (:import (java.io File FileInputStream FileOutputStream) (java.nio.channels FileChannel))
  (:require [clojure.set :as set]))

(defn- copy-file [src dst]
  (if (.isDirectory src)
    (.mkdir dst)
    (with-open
        [src (.getChannel (FileInputStream. src))
         dst (.getChannel (FileOutputStream. dst))]
      (. src transferTo 0 (.size src) dst)
      )))

(defn- scan-dir
  ([dir]
     (scan-dir dir nil (set [])))
  ([dir parent interm]
     (reduce (fn [files f]
               (conj
                (if (.isDirectory f) (scan-dir f (File. parent (.getName f)) files) files)
                (.getPath (File. parent (.getName f))))
                ) interm (.listFiles dir)))
  )

(defn update-output
  "Builds the output directory from contents of the input
  directory. Avoids copying files when they already exist."
  [input-dir output-dir]

  (when (= input-dir output-dir)
    (throw (IllegalArgumentException.
            (str "Input and output directory must be different: "
                 input-dir))))

  (println "Copying from" input-dir "to" output-dir)
  
  ;; ensure output directory exists
  (.mkdir (File. output-dir))

  (if input-dir
    (let [inputs (scan-dir (File. input-dir))
          outputs  (scan-dir (File. output-dir))]

      ;; Delete unknown outputs. Reverse sort to delete contents of
      ;; directories before directories themselves.
      (doseq [f (reverse (sort (seq (set/difference outputs inputs))))]
        (println "Delete output:" f)
        (.delete (File. output-dir f)))

      ;; Compare files that exist in both input and output.
      (doseq [f (set/intersection inputs outputs)]
        (println "Same:" f)
        (let [src (File. input-dir f) dst (File. output-dir f)]

          ;; files are 'different'
          (when-not
              ;; or of reasons not to copy the file
              (or
               ;; both are directories
               (and (.isDirectory src) (.isDirectory dst))
               ;; identical files
               (and (and (.isFile src) (.isFile dst))
                    (= (.length src) (.length dst))
                    (< (.lastModified src) (.lastModified dst))))
            (do
              (println "Copy modified:" f)
              (.delete dst)
              (copy-file src dst)))))

      ;; Copy brand new files. Sort to copy directories before contents.
      (doseq [f (sort (seq (set/difference inputs outputs)))]
        (println "Copy:" f)
        (let [src (File. input-dir f) dst (File. output-dir f)]
          (println "Copying:" src dst)
          (copy-file src dst))))
    ;; No input directory.. Just clear the output directory. Reverse
    ;; to delete files before directories
    (doseq [f (reverse (sort (seq (scan-dir (File. output-dir)))))]
        (println "Delete output:" f)
        (.delete (File. output-dir f)))))

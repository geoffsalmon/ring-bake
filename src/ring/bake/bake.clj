(ns ring.bake.bake
  "Bake a static website from a Ring handler."
  (:require [ring.bake staticfiles [image :as image]])
  (:require [clojure.contrib
             [duck-streams :as io]
             [string :as string]])
  (:require [clojure.java.io :as jio])
  (:use [clojure.contrib.except :only [throwf]])
  (:use clojure.contrib.java-utils)
  (:require (ring.util [codec :as codec]
                       [response :as response]))
  (:require [ring.mock.request :as mock])

  (:import (java.io File InputStream FileInputStream BufferedReader InputStreamReader)))


(defn- do-request [uri req-func]
  "Send a request for the given uri to the handler req-func. Returns
the body of the result."
  (:body (req-func (mock/request :get uri))))

(defn- bake-to-file [dir uri req-func]
  (println "bake" uri)
  (let [body (do-request uri req-func)
        outfile (apply jio/file dir (string/split #"/" uri))
        outfile (if (.isDirectory outfile) (jio/file outfile "index.html") outfile)]
    (.mkdirs (.getParentFile outfile))
    ;; Write body to outfile. Handle the same response body types that
    ;; ring does.
    (cond
     (string? body)
     (io/spit outfile body)
       
     (seq? body)
     (with-open [writer (io/writer outfile)]
       (doseq [chunk body]
         (.print writer (str chunk))
         ))
     
     (instance? InputStream body)
     (io/copy body outfile)
     
     (instance? File body)
     (io/copy body outfile)
     
     (nil? body)
     nil
     
     :else
     (throwf "Unrecognized body: %s" body))
    )
  )

;; The context of the current call to bake. If bake has not been
;; called, context is nil.
(def ^{:private true} *context* nil)

(defn bake-context
  "External access to the bake context. Useful when you need to change
the pages being rendered based on whether the current requests is from
jetty, a local bake or a bake meant to be deployed.

TODO: Is the entire context really useful? Maybe just return one of
three keyworks :http :bake :bake-local"
  [] *context*)

(defn- calc-depth
  "Helper function to count the number of directories in path for the
  context."
  [uri]
  (- (count (string/split  #"/" (if (= (string/tail 1 uri) "/") (str uri "index.html") uri))) 2))

(defn- bake-file
  "Bakes a single file. Returns a sequence of files that were linked to."
  [output-dir rel-uri req-func is-local]
  ;; merge details relevant to this specific file into the context
  (binding [*context*
            (merge
             *context*
             {:links (atom #{})
              :rev-path (rest (reverse (string/split #"/" rel-uri)))
              :depth (calc-depth rel-uri)
              })]
    (bake-to-file output-dir rel-uri req-func)
    ;; Check links for other local pages to bake. Need to
    ;; check that links don't already exist in the output.
    @(:links *context*)))


(defn bake
  "Bakes a static website in the output-dir that contains both the
static content from the input-dir and the dynamic content obtained
from the req-func. First, anything in the output-dir that isn't in the
input-dir is deleted, then newer files from the input-dir are copied
to the output-dir. Finally, requests are made from the starting
url (default \"/\") are sent to req-func to obtain the site's dynamic
content.

Options:
:start \"/foo.html\"
- specifies where baking will start from
:force-relative true
- makes internal links relative. Useful if you want to open the files in a browser.
"
  ;; TODO: Make input-dir optional
  ;; TODO: More options for controlling what will be baked! Multiple
  ;; start locations, filter functions for requests URLs, etc.
  ;; TODO: Add option for index names. default "index.html"
  ;; TODO: Add option for site root which will be added to absolute links. default "/"
  [req-func input-dir output-dir & {:keys [force-relative start] :or {start "/"}}]
  (println "bake from" input-dir "to" output-dir "start:" start (when force-relative "force-relative"))
  
  (ring.bake.staticfiles/update-output input-dir output-dir)
  (binding [*context* {:baking true
                       :input-dir input-dir
                       :output-dir output-dir
                       :local (if force-relative true false)}]
    (loop [uris #{start}]
      (if-let [uri (first uris)]
        (let [new-links (bake-file output-dir uri req-func force-relative)

              ;; combine all known links
              all-links (into (into #{} (rest uris)) new-links)
              ;; remove those links that already exist
              bake-links (filter #(not (.exists (jio/file (str output-dir %)))) all-links)
              ]
          (recur bake-links))))))

(defn- rel-to-abs
  "Converts a relative link to one that is absolute from the root of the site.
link is the string path. rev-path is the reversed list of path
elements of the page currently being served."
  [link rev-path]
  (loop [prefix rev-path
         suffix (string/split #"/" link)]
    (if suffix
      (if (= ".." (first suffix))
        (recur (pop prefix) (next suffix))
        (recur (conj prefix (first suffix)) (next suffix))
        )
      (apply str (interpose "/" (reverse prefix)))))
  )

(defn- is-abs?
  "Returns true if the given path string is absolute."
  [path]
  (= (string/take 1 path) "/"))

(defn- process-path
  "Given a path, returns a vector containing the same path as relative and absolute"
  [path]

  (if (is-abs? path)
    [(let [path (string/drop 1 path)
           depth (or (:depth *context*) 0)]
       (str (apply str (repeat depth "../")) path))
     path]

    [path
     (rel-to-abs path (:rev-path *context*))]))

(defn local-uri
  "Creates a local link within the website. This functions serves a
few purposed. During baking, it informs about new request that may
need to be baked. Also, when baking a local version of the site this
will convert absolute paths to relative ones."
  [path]
  (let [[rel-path abs-path] (process-path path)]

    ;; Store path in links list so we will bake it if necessary
    (if-let [links (:links *context*)]
      (swap! links conj abs-path))
    
    ;; baking locally requires a relative path
    (if (:local *context*)
      (if (= (string/tail 1 path) "/")
        (str rel-path "index.html")
        rel-path)
      path)))

(defn- encode-image-options
  "Encode an image transformation options in an image path."
  [path & opts]
  (let [parts (string/split #"\." path)]
    (when [>= (count parts) 2]
      (let [basename (string/join "." (butlast parts))
            ext (last parts)
            opt-strs
            (map
             (fn [[opt value]]
               (str "." (name opt) "="
                    (if (keyword? value)
                      (name value)
                      value)))
             (partition 2 opts))]
        (str basename (apply str opt-strs) "." ext)))))

(defn- decode-image-options [path]
  (let [path-parts (string/split #"/" path)
        filename (last path-parts)
        parts (string/split #"\." filename)]
    
    (when (>= (count parts) 2) 
      (let [ext (last parts)
            real-name (str (first parts) "." ext)
            options (rest (butlast parts))]
        ;; TODO: Make filtering extensions more flexible. Maybe add
        ;; options to wrap-resize-img?
        (when (get #{"png" "gif" "jpg"} ext)
          (let [opt-map
                (reduce (fn [opts opt]
                          (if-let [[key val] (next (re-matches #"(.*)=(.*)" opt))]
                            (let [key (keyword key)]
                              (assoc opts key
                                     ((or
                                       ;; convert the values of some
                                       ;; options to other types
                                       ({:w #(Integer/parseInt %)
                                         :h #(Integer/parseInt %)
                                         :extra keyword} key)
                                       identity) val)))
                            (assoc opts (keyword opt) true)))
                        {} options)]
            {:filename real-name
             :ext ext
             :opts (flatten (seq opt-map))}))))))

(defn image [path & opts]
  "Creates an image src path and determines the width and height of
the image. Returns a vector [image_src, width, height] which can be
used to build an HTML img tag.

Without any options the resulting img_src is the same as the path
argument and the returned width and height are the dimensions of the
image file. Options can be used to modify the image.

Options:

:w int
- Sets a maximum width for the image

:h int
- Sets a maximum height for the image

:extra (:ignore|:pad|:stretch)
- Determines what to do when only one of :w and :h is specified or
  when both options are used but the aspect ratio does not match the
  image's. The deafult value is :ignore which will maintain the aspect
  ratio, shrinking either the width or height until the minimum w and
  h values are satisfied. Using :pad will pad the resulting image with
  a solid color so that the resulting image is the requested
  size. The last option is :stretch which stretches the image to the
  requested size, ignoring the aspect ratio.

:pad-color String
- Sets the color used to pad the image with. Currently this only
  accepts a short list of color names. In future it should take RGB
  colors.
"
  
  (let [[_ abs-path] (process-path path)
        rel-from-root (string/drop 1 abs-path)]
    ;; this is called while serving a page. Try to find the image by
    ;; checking the directories passed to the surrounding
    ;; wrap-resize-img
    (loop [dirs (seq (:img-dirs *context*))]
      (if dirs
        (let [dir (first dirs)
              img-file (jio/file dir rel-from-root)]
          (if (.exists img-file)
            (let [{[width height] :final-size} (apply image/create-pipeline img-file opts)]
              [(local-uri (apply encode-image-options path opts)) width height])

            ;; doesn't exist. look in other dirs
            (recur (next dirs))))

        ;; not found in any dirs. Return the path unchanged with
        ;; request width and height
        (let [{:keys [w h]} opts]
          [(local-uri path) w h])))))

(defn- attempt-process-img [root-dir path]
  (let [{:keys [filename ext opts]} (decode-image-options path)]
    (if filename
      (let [file (File. (.getParentFile (File. root-dir path))
                        filename)]
        (when (.exists file)
          [(apply image/process-img (image/read-file file) opts)
           ext])))))

(defn- img-response [img ext]
  (when img
    (let [bytes (java.io.ByteArrayOutputStream.)]
      (javax.imageio.ImageIO/write img ext bytes)
      (response/response (java.io.ByteArrayInputStream. (.toByteArray bytes))))))

(defn wrap-resize-img
  "A Ring middleware that adds additional directories that images can
be served from. These images can be resized dynamically and the
full-size originals don't need to appear in the input directory passed
to bake."
  [app input-dir]
  (fn [req]
    (if-let [[img ext]
             (when (= :get (:request-method req))
               (let [path (.substring (codec/url-decode (:uri req)) 1)]
                 (attempt-process-img input-dir path)))]
      (img-response img ext)

      ;; add the input dir to img-dirs list in the context and pass
      ;; request to the rest of the handlers
      (binding [*context*
                (assoc *context* :img-dirs (conj (:img-dirs *context*) input-dir))]
        (app req)))))

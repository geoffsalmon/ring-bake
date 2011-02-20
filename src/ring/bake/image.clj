(ns ring.bake.image
  "Utilities for reading and transforming image files."
  (:use [clojure.contrib.java-utils :only [as-file]])
  (:import
   [java.awt Transparency RenderingHints Color]
   [java.awt.image BufferedImage]
   [java.io File]))

(defn read-file [path]
  (javax.imageio.ImageIO/read (as-file path)))

(defn write-file
  ([img path]
     (write-file img path "png"))
  ([img path ext]
      (javax.imageio.ImageIO/write img ext (as-file path))))

(defprotocol ImgSize
  (get-size [img] "Return the dimensions of the img in pixels [x y]")
  (to-image [img] "Coerce to a BufferedImage"))

(extend-type BufferedImage
  ImgSize
  (get-size [img] [(.getWidth img) (.getHeight img)])
  (to-image [img] img))

(extend-type String
  ImgSize
  ;; reading in the entire image and decoding it just to get the image
  ;; dimensions is wasteful! Is there a faster way that reads only the
  ;; header or should we cache the results?
  (get-size [img] (get-size (read-file img)))
  (to-image [img] (read-file img)))

(extend-type File
  ImgSize
  ;; reading in the entire image and decoding it just to get the image
  ;; dimensions is wasteful! Is there a faster way that reads only the
  ;; header or should we cache the results?
  (get-size [img] (get-size (read-file img)))
  (to-image [img] (read-file img)))

(defprotocol ToColor
  (to-color [color] "Coerces a value to a java.awt/Color"))

(extend-type Color
  ToColor
  (to-color [color] color))

(extend-type Iterable
  ToColor
  (to-color
   [[r g b a]]
   (if (and (integer? r) (integer? g) (integer? b) (or (nil? a) (integer? a)))
     ;; all integers
     (if (nil? a)
       (Color. (int r) (int g) (int b))
       (Color. (int r) (int g) (int b) (int a)))
     ;; all floats
     (if (nil? a)
       (Color. (float r) (float g) (float b))
       (Color. (float r) (float g) (float b) (float a))))))

(extend-type String
  ToColor
  (to-color
   [color]
   ;; TODO: parse "[80,0,100]" or "[0.4,1,0]" but not sure how to
   ;; encode the "." in the path...
   (case color
         "black" Color/black
         "white" Color/white
         "red" Color/red
         "green" Color/green
         "blue" Color/blue
         "yellow" Color/yellow
         )))

(defn- best-type [img]
  (if (= (.getTransparency img)
         Transparency/OPAQUE)
    BufferedImage/TYPE_INT_RGB
    BufferedImage/TYPE_INT_ARGB))

(defn scale-down
  "Scales an image down with repeated drawImage calls.
This approach is suggested here http://today.java.net/pub/a/today/2007/04/03/perils-of-image-getscaledinstance.html"

  [img target-w target-h hint]
  (let [type (best-type img)
        [w h] (get-size img)]

    ;;(println "Scale down from " w "x" h  " to " target-w "x" target-h)

    (loop [w w
           h h
           img img]
      (let [w (Math/max target-w (int (/ w 2)))
            h (Math/max target-h (int (/ h 2)))

            down-img (BufferedImage. w h type)]
        ;;(println "Scale to " w "x" h)
        (doto (.createGraphics down-img)
          (.setRenderingHint RenderingHints/KEY_INTERPOLATION
                             hint)
          (.drawImage img 0 0 w h nil)
          (.dispose))

        (if (and (= w target-w) (= h target-h))
          down-img
          (recur w h down-img))))))

(defn pad [img color t r b l]
  (let [[w h] (get-size img)
        pad-w (+ w r l)
        pad-h (+ h t b)
        pad-img (BufferedImage. pad-w pad-h (best-type img))]

    (doto (.createGraphics pad-img)
      ;; fill with the padding color. might be faster to draw just
      ;; in the padding area
      (.setColor (to-color color))
      (.fillRect 0 0 pad-w pad-h)
      (.drawImage img nil l t)
      (.dispose))
    pad-img))

;; Options for process? final width, final height are most important,
;; but, unless the aspect ratio of the source image and requested sizes
;; match, there are additional params to determine how the scaling is
;; done.
;; TODO: Support cropping and setting pad colour

;; Case 1: request dims both <=,

;;  Scale down! Need to fit image into that box. Unless aspect ratios
;;  match, one requested dimension will be too large. Either pad or
;;  stretch in that dimension. When padding, allow a colour option.

;; Case 2: one request dim is >

;;  DO NOTHING FOR NOW. Return image unchanged.
;;  Avoid upscaling! Let the browser handle that. If padding is set,
;;  could add padding in one dimension to maintain the aspect ratio
;;  assuming the image will be stretched to the given dims or could
;;  add padding in both dimensions to create a larger image with the
;;  unstretched image centered.

(defn- pos-int? [x] (and (integer? x) (> x 0)))
(defn create-pipeline
  "Computes the final size of the img would be when processed when the
  image is the given size and the same options are passed to process."
  [img & {:keys [w h extra pad-color] :or {extra :ignore}}]
  {:pre [(or (nil? w) (pos-int? w))
         (or (nil? h) (pos-int? h))
         (#{:ignore :pad :stretch} extra)]}

  (let [[img-w img-h] (get-size img)]
    (if (and
         ;; don't scale at all if neither w or h options set
         (or w h)
         ;; ensure the requested w and h aren't upscaling
         (or (nil? w) (<= w img-w)) (or (nil? h) (<= h img-h)))
         
      ;; is not an upscale in either dimension
      (let
          [;; replace nil with actual size
           w (or w img-w)
           h (or h img-h)]
  
        ;; the final size of a stretched image is easy
        (if (= :stretch extra)
          {:ops [:scale [w h]]
           :final-size [w h]}

          (let [;; compute ratios. 0 < ratio-* <= 1
                ratio-w (/ w img-w)
                ratio-h (/ h img-h)

                [final-w final-h]
                (if (< ratio-w ratio-h)
                  ;; shrink to requested w
                  [w (int (* img-h ratio-w))]
                  ;; shrink to requested h
                  [(int (* img-w ratio-h)) h])]
            (case extra
                  :pad {:ops (concat
                              [:scale [final-w final-h]]
                              (when pad-color
                                [:pad-color pad-color])
                              [:pad (let [lr (/ (- w final-w) 2.0)
                                          tb (/ (- h final-h) 2.0)]
                                      [(int (Math/floor tb)) (int (Math/ceil lr))
                                       (int (Math/ceil tb)) (int (Math/floor lr))])])
                        :final-size [w h]}
                  :ignore {:ops [:scale [final-w final-h]]
                           :final-size [final-w final-h]}))))

      ;; is an upscale. Do nothing for now. Handle this better later
      (do
        {:final-size [(or w img-w) (or h img-h)]}))))

(defn- css-args [args]
  (case (count args)
        1 (let [all (first args)] [all all all all])
        2 (let [[tb rl] args] [tb rl tb rl])
        3 (let [[t rl b] args] [t rl b rl])
        4 args))

(defn process-pipeline [img pipeline]
  ;; What is pipeline?
  ;; :scale [w h]
  ;; :crop [x y w h]
  ;; :slice [top right bottom left] or [top right-left bottom] or
  ;;        [top-bottom right-left] or [all-edges]
  ;; :pad [top right bottom left] or [top right-left bottom] or
  ;;      [top-bottom right-left] or [all-edges]

  (first
   (reduce
    (fn [[img state] [op args]]
      (case op
            :scale (let [[w h] args] [(scale-down img w h RenderingHints/VALUE_INTERPOLATION_BILINEAR) state])
            :crop (let [[x y w h] args] [(.getSubimage img x y w h) state])
            :slice (let [[t r b l] (css-args args)
                         [w h] (get-size img)]
                     [(.getSubimage img l t (- w r l) ( - h t b))] state)
            :pad-color [img (assoc state :pad-color (to-color args))]
            :pad [(apply pad img (:pad-color state) (css-args args)) state]
            )) [img {:pad-color Color/white}] (partition 2 pipeline))))

(defn process-img [img & options]
  (let [img (to-image img)
        {ops :ops} (apply create-pipeline img options)]
    (process-pipeline img ops)))

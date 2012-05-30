(ns ring.bake.ex
  (:require [ring.bake [bake :as bake]])
  (:require [ring.util.response :as response])
  (:use net.cgrand.moustache))


(defn a [uri body] (str "<a href=\"" (bake/local-uri uri) "\">" body "</a>"))

(def routes
  (app
   []
   (fn [req]
     (response/response
      (str
       "<html><body>"
       (a "/1/page.html" "link") "<br>"
       (a "/2/page.html" "other link")
       "</body></html>")))
   
   [param "page.html"]
   (fn [req]
     (response/response
      (str
       "<html><header><title>Page " param
       "</title></header><body>"
       (a "/" "main page")
       "</body></html>")))))

(defn bake []
  (bake/bake routes "output")
  (bake/bake routes "output-rel" :force-relative true))

ring-bake
=========

Ring-bake is a Clojure library for creating static websites. It is
built on [Ring](https://github.com/mmcgrana/ring) and sends requests
to a Ring handler function to build the content that will be saved as
static files.

Intended Use
------------

Let's say you want to create a static web site for either the
simplicity of hosting, the speed of serving static content, or
some other reason. However, you also want to build the site using all
of the Clojure web libraries you're used to. Ring-bake let's you do
both.

Create the site as you normally would, using a local Jetty server to
develop it interactively. When you're happy with the results, take the
same Ring handler function that Jetty is using and pass it to
ring-bake's bake function. It will send mock http requests to the
handler and combine the resulting response bodies with static files
from a specified directory to create the static site that you can then
copy to your webhost.

Baking
------

The main entry point is the function ring.bake.bake/bake (TODO: That's
a lot of "bake"! Reorganize things?). 

    (defn app [req]
      ;; a Ring handler function
    )
    (bake/bake app "output" :input-dir "input")

The above will copy all static content from directory `input` to
directory `output` and also save the dynamic content returned by the
handler. By default, the first request URI used is "/".

Sometimes it is useful to have a static site where all of the internal
links are relative. Such a site can be opened directly by a browser
and doesn't need a web server. Passing the option `:force-relative
true` to the bake function will make all of the local links
relative. Ring-bake does not parse the resulting HTML, so to modify
the links, you must use the `local-uri` function, described below.


Local Links
-----------

Ring-bake needs a hook into the site creation process to discover what
URIs need to be requested.

    (bake/local-uri path)

Informs ring-bake of the path to another page within the site. This
also returns a possibly modified version of the path which should
be used in the resulting page.

Call the function `local-uri` as you are building the reponse pages
in the context of calls to the Ring handler. 

Example
-------

Here is a simple but complete example:

    (ns ring.bake.ex
      (:require [ring.bake [bake :as bake]])
      (:require [ring.util.response :as response])
      (:use net.cgrand.moustache))
    
    (defn a [uri body] 
      (str "<a href=\"" (bake/local-uri uri) "\">" body "</a>"))
    
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

I've tried to minimize the dependencies so the example is creating the
string bodies by hand. In a real application you would want to use a
library like Hiccup or Enlive. The one additional dependency is on
moustache for routing, which requires adding `[net.cgrand/moustache
"1.0.0-SNAPSHOT"]` to `project.clj` although you could use Compojure
instead.

If you call the `bake` function, the site will be baked twice. After
baking, look in the directories output and output-rel. Both should
contain the files

    index.html
    1/page.html
    2/page.html

To see the effect of the `:force-relative` option. Compare the href of
the link in `output/other/1/page.html`

    <a href="/">main page</a>

with the href in `output-rel/1/page.html`

    <a href="../index.html">main page</a>

You can open the output of baking with `force-relative` in a browser
and the paths to the pages, style sheets and images will be valid as
long as you passed them through `bake/local-uri` when building the
page.

Images
------

Ring-bake helps with resizing images. When building the site content,
call

    (bake/image path & options)

which returns a vector [src width height] which you can put straight
into the img tag. The options :w and :h will scale the image down to
fit without the specified dimensions.

For example, if `image.jpg` is a 1000x600 image then `(bake/image
"image.jpg" :w 200)` would return `["image.w=200.jpg" 200 120]`. As
you can see, the height is decreased to 120 to maintain and the
option is encoded in the new path as `w=200`. To serve these resized
images use the ring middlewware

    (bake/wrap-resize-img app img-dir)

In addition to calling this middleware with the same static input
directory that you pass to bake/bake, you can call wrap-resize-img
with other directories containing large images. The resized versions
of the images will appear in your baked output, but the originals will
not.

License
-------

Copyright (c) 2011 Geoffrey Salmon

Distributed under the Expat License

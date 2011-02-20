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
    (bake/bake app "input" "output")

The above will copy all static content from directory input to
directory output and also save the dynamic content returned by the
handler.

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

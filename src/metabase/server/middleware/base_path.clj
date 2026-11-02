(ns metabase.server.middleware.base-path
  "Mount the complete HTTP application under a configured path."
  (:require
   [clojure.string :as str]
   [metabase.config.core :as config])
  (:import
   (java.net URI)))

(set! *warn-on-reflection* true)

(defn- site-uri
  [base-path]
  (let [url (config/config-str :mb-site-url)
        uri (when url (URI. url))]
    (when-not (and uri
                   (#{"http" "https"} (.getScheme uri))
                   (.getHost uri)
                   (nil? (.getUserInfo uri))
                   (nil? (.getRawQuery uri))
                   (nil? (.getRawFragment uri))
                   (= base-path (str/replace (.getRawPath uri) #"/$" "")))
      (throw (ex-info "MB_SITE_URL must be an absolute HTTP(S) URL whose path matches MB_BASE_PATH"
                      {:base-path base-path})))
    uri))

(defn- mounted-path?
  [base-path path]
  (or (= base-path path) (str/starts-with? path (str base-path "/"))))

(defn- mounted-location
  [base-path ^URI site location]
  (try
    (let [uri (URI. location)
          path (.getRawPath uri)
          local? (or (and (nil? (.getScheme uri))
                          (nil? (.getRawAuthority uri))
                          (str/starts-with? location "/"))
                     (and (= (.getScheme site) (.getScheme uri))
                          (= (.getRawAuthority site) (.getRawAuthority uri))))]
      (if (and local? path (not (mounted-path? base-path path)))
        (str (when (.getScheme uri) (str (.getScheme uri) "://" (.getRawAuthority uri)))
             base-path (if (empty? path) "/" path)
             (when-some [query (.getRawQuery uri)] (str "?" query))
             (when-some [fragment (.getRawFragment uri)] (str "#" fragment)))
        location))
    (catch java.net.URISyntaxException _ location)))

(defn- mount-response
  [base-path site response]
  (reduce (fn [response header]
            (if-let [location (get-in response [:headers header])]
              (assoc-in response [:headers header] (mounted-location base-path site location))
              response))
          response
          ["Location" "location"]))

(defn wrap-base-path
  "Accept only requests inside MB_BASE_PATH, and keep local redirects inside that mount."
  [handler]
  (let [base-path (config/base-path)]
    (if (empty? base-path)
      handler
      (let [site (site-uri base-path)]
        (fn [request respond raise]
          (let [uri (:uri request)]
            (cond
              (= uri base-path)
              (respond {:status 308
                        :headers {"Location" (str base-path "/"
                                                  (when-some [query (:query-string request)] (str "?" query)))}
                        :body ""})

              (str/starts-with? uri (str base-path "/"))
              (let [path (subs uri (count base-path))]
                (if (str/starts-with? path "//")
                  (respond {:status 400 :body "Invalid path"})
                  (handler (cond-> (assoc request :uri path)
                             (:path-info request) (assoc :path-info path))
                           #(respond (mount-response base-path site %))
                           raise)))

              :else
              (respond {:status 404 :body "Not found."}))))))))

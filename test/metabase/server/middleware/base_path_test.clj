(ns metabase.server.middleware.base-path-test
  (:require
   [clojure.test :refer :all]
   [metabase.config.core :as config]
   [metabase.request.cookies :as cookies]
   [metabase.server.middleware.base-path :as base-path]
   [metabase.server.middleware.browser-cookie :as browser-cookie]
   [metabase.test :as mt]))

(set! *warn-on-reflection* true)

(defn- response
  [handler request]
  (let [result (promise)]
    (handler request #(deliver result %) #(deliver result %))
    (let [value (deref result 1000 ::timeout)]
      (when (instance? Throwable value) (throw value))
      value)))

(defn- mounted-handler
  [path url handler]
  (mt/with-dynamic-fn-redefs [config/config-str (fn [k] ({:mb-base-path path :mb-site-url url} k))]
    (base-path/wrap-base-path handler)))

(deftest ^:parallel mount-test
  (let [handler (mounted-handler "/internal/bi" "https://example.com/internal/bi"
                                 (fn [request respond _] (respond {:status 200 :body request})))
        request {:uri "/internal/bi/api/session" :path-info "/internal/bi/api/session"
                 :request-method :post :query-string "a=%2F" :body :unchanged}]
    (is (= (assoc request :uri "/api/session" :path-info "/api/session")
           (:body (response handler request))))
    (doseq [path ["/" "/api/session" "/internal/bix/api/session" "/internal/bi-evil/"]]
      (is (= 404 (:status (response handler {:uri path})))))
    (is (= 400 (:status (response handler {:uri "/internal/bi//evil"}))))
    (is (= {:status 308 :headers {"Location" "/internal/bi/?x=%2F"} :body ""}
           (response handler {:uri "/internal/bi" :query-string "x=%2F"}))))
  (let [handler (fn [request respond _] (respond {:status 200 :body request}))]
    (doseq [path [nil "" "/"]]
      (is (identical? handler (mounted-handler path nil handler))))))

(deftest ^:parallel redirect-test
  (doseq [[location expected] [["/auth/login?next=%2F#x" "/internal/bi/auth/login?next=%2F#x"]
                               ["/internal/bi/auth/login" "/internal/bi/auth/login"]
                               ["/internal/bix/" "/internal/bi/internal/bix/"]
                               ["https://example.com/auth/login" "https://example.com/internal/bi/auth/login"]
                               ["https://example.com/internal/bi/" "https://example.com/internal/bi/"]
                               ["https://other.example/auth" "https://other.example/auth"]
                               ["//other.example/auth" "//other.example/auth"]
                               ["next" "next"]]]
    (doseq [header ["Location" "location"]]
      (let [handler (mounted-handler "/internal/bi" "https://example.com/internal/bi"
                                     (fn [_ respond _] (respond {:status 302 :headers {header location}})))]
        (is (= expected (get-in (response handler {:uri "/internal/bi/"}) [:headers header])))))))

(deftest ^:parallel invalid-configuration-test
  (doseq [path ["internal/bi" "//internal/bi" "/internal/../bi" "/internal/%62i" "/internal/bi/" "/internal/bi?x"]]
    (is (thrown? Exception (mounted-handler path "https://example.com/internal/bi" identity))))
  (doseq [url [nil "https://example.com" "https://example.com/other" "https://example.com/internal/bi?x"
               "https://example.com/internal/bi#x" "https://user@example.com/internal/bi"]]
    (is (thrown? Exception (mounted-handler "/internal/bi" url identity)))))

(deftest cookie-path-test
  (doseq [[path expected] [[nil "/"] ["/internal/bi" "/internal/bi/"]]]
    (mt/with-dynamic-fn-redefs [config/config-str (fn [k] (when (= k :mb-base-path) path))]
      (let [handler (browser-cookie/ensure-browser-id-cookie
                     (fn [_ respond _] (respond {:status 200})))]
        (is (= expected (get-in (response handler {:scheme :http :headers {}})
                                [:cookies "metabase.DEVICE" :path]))))
      (doseq [type [:normal :full-app-embed]]
        (is (= expected (:path (cookies/default-session-cookie-attributes type {})))))
      (is (= #{expected}
             (set (map :path (vals (:cookies (cookies/clear-session-cookie {:status 200}))))))))))

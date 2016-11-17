(ns clj-infrastructure.dropbox.client.v1-test
  "Unit tests for Dropbox API (v1)"
  (:require
    [clojure.test                         :refer :all]
    [clojure.tools.logging                :as log]
    [clojure.pprint                       :as pp]
    [clojure.java.io                      :as jio]
    [clojure.string                       :as string]
    [clj-foundation.unit-test-common      :as common]
    [clj-foundation.config                :refer :all]
    [clj-infrastructure.dropbox.client.v1 :as dbox]

    ; 3rd party libs
    [cemerick.url       :as url]
    [cheshire.core      :as json]
    [clj-oauth2.client  :as oauth2]
  )
  (:gen-class))

; CONFIG (env: $CLJ_INFRA_CONFIG_PATH):
;
; { :dropbox {
;     :apps [
;       { :app-name "Dataservices ETL App 001"
;         :auth {
;           :method   "access-token"   ; ("key", "oauth", "oauth2", "access-token")
;           :username "dataservices@bradsdeals.com"
;           :password "<redacted-site-password>"
;           :oauth2-access-token "<redacted-app-access-token>" } }]} }

(def common-config-file-path  "CLJ_INFRA_CONFIG_PATH")
(def default-config-file-path "config/config.edn")

(try
  ; TODO: Cache config file contents; this shouldn't be re-reading entire config each time
  (defconfig secret-config common-config-file-path default-config-file-path)
  (catch Exception e
    (throw (ex-info "Unable to locate configuration" {:env-var-name common-config-file-path :default-config-file-path default-config-file-path}))))

(def tmp-dir                "/tmp")
(def tmp-file-name-base     (str "unit-test." (str *ns*)))
(def tmp-file-name-ext      ".tmp")

(def dbox-app-conf          (first (filter #(= (:app-name %) "Dataservices ETL App 001") (secret-config :dropbox :apps))))
(def dbox-auth-access-token (get-in dbox-app-conf [:auth :oauth2-access-token]))
(def dbox-upload-path       "/BradsDeals")
(def dbox-upload-file-name  "file-upload-test.txt")

(common/register-fixtures)

(defn test-fixture-each
  [test-fn]
  (test-fn)
)

(defn test-fixture-once
  [test-fn]
  (test-fn)
)

(defn ->tmp-file-name
  [& [suffix file-name-base file-name-ext]]
  (str
    (or file-name-base tmp-file-name-base)
    (when suffix (str "." suffix))
    (or file-name-ext tmp-file-name-ext)))

(defn ->tmp-file-path
  [& [base-path file-name]]
  (string/join "/"
    [(or base-path tmp-dir)
     (or file-name (->tmp-file-name))]))

(defn create-tmp-file
  "Create a tmpoaray file.
   @return result-vec [file-name contents]"
  [& [path]]
  (let [rand-num  (rand-int 999999999)
        file-path (->tmp-file-path path (->tmp-file-name rand-num))]
    (spit file-path (str rand-num))
    [file-path rand-num]))

(deftest file-transfer
  (testing "Validate we can put and retrieve a file on remote dropbox"

  (let [[tmp-file-path contents] (create-tmp-file)
         download-file-path      (->tmp-file-path)]

    (try

      (testing "Uploading file - credentials passed as param "
        (let [
          result
              (dbox/file-stream->dbox-file
                (jio/input-stream (jio/file tmp-file-path))
                (string/join "/" [dbox-upload-path dbox-upload-file-name])
                {:access-token dbox-auth-access-token})]
          (is (= (:status result) 200))))

      (testing "Downloading file - credentials passed via macro binding"
        (let [
          download-file-path  (->tmp-file-path)
          result
            (dbox/with-credentials dbox-auth-access-token
              (let [remote-file-contents  (slurp (dbox/dbox-file->file-stream (string/join "/" [dbox-upload-path dbox-upload-file-name]))) ]
                remote-file-contents))]
          (is (= (str result) (str contents)))))

    (catch Exception e
      (log/error "Exception running unit test: " e)
      (throw e))
    (finally
      (let [download-file-handle  (jio/file tmp-file-path)
            upload-file-handle    (jio/file download-file-path)]
        (when (.exists download-file-handle)
          (jio/delete-file download-file-handle))
        (when (.exists upload-file-handle)
          (jio/delete-file upload-file-handle))))))))


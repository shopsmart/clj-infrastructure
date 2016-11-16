(ns clj-infrastructure.dropbox.client.v1
  "Wrapper for Dropbox API (v1)"
  (:require
    [clojure.java.io          :as jio]
    [clojure.tools.logging    :as log]
    [clojure.pprint           :as pp]
    [clojure.java.shell       :as sh]
    [clojure.string           :as string]

    ; 3rd party libs
    [cemerick.url       :as url]
    [cheshire.core      :as json]
    [clj-oauth2.client  :as oauth2]
  )
  (:gen-class))

; Map of Dropbox version 1 API metadata

(def ^:private dbox-url-www     "https://www.dropboxapi.com/1")
(def ^:private dbox-url-api     "https://api.dropboxapi.com/1")
(def ^:private dbox-url-api-2   "https://api.dropbox.com/1")
(def ^:private dbox-url-content "https://content.dropboxapi.com/1")

; API metadata - URL groups separated by newline
(def api-meta-map {
  :authorize {
    :url    (format "%s/oauth/authorize" dbox-url-www)
    :method "GET"}

  :request-token {
    :url    (format "%s/oauth/request_token" dbox-url-api)
    :method "POST"}
  :access_token {
    :url    (format "%s/oauth/access_token" dbox-url-api) 
    :method "POST"}
  :token {
    :url    (format "%s/oauth2/token" dbox-url-api) 
    :method "POST"}
  :token_from_oauth1 {
    :url    (format "%s/oauth2/token_from_oauth1" dbox-url-api) 
    :method "POST"}
  :disable_access_token {
    :url    (format "%s/disable_access_token" dbox-url-api) 
    :method "POST"}
  :info {
    :url    (format "%s/account/info" dbox-url-api) 
    :method "GET"}
  :metadata {
    :url    (format "%s/metadata/auto" dbox-url-api)
    :method "GET"}
  :link {
    :url    (format "%s/metadata/link" dbox-url-api-2) 
    :method "POST"}
  :delta {
    :url    (format "%s/delta" dbox-url-api) 
    :method "POST"}
  :latest_cursor {
    :url    (format "%s/delta/latest_cursor" dbox-url-api) 
    :method "POST"}
  :revisions {
    :url    (format "%s/revisions/auto" dbox-url-api)
    :method "GET"}
  :restore {
    :url    (format "%s/restore/auto" dbox-url-api)
    :method "POST"}
  :search {
    :url    (format "%s/search/auto" dbox-url-api)
    :method "GET"}
  :shares {
    :url    (format "%s/shares/auto" dbox-url-api)
    :method "POST"}
  :media {
    :url    (format "%s/media/auto" dbox-url-api)
    :method "POST"}
  :copy_ref {
    :url    (format "%s/copy_ref/auto" dbox-url-api)
    :method "GET"}
  :shared_folders {
    :url    (format "%s/shared_folders" dbox-url-api)
    :method "GET"}
  :save_url {
    :url    (format "%s/save_url/auto" dbox-url-api)
    :method "POST"}
  :save_url_job {
    :url    (format "%s/save_url_job" dbox-url-api)
    :method "GET"}
  :copy {
    :url    (format "%s/fileops/copy" dbox-url-api) 
    :method "POST"}
  :create_folder {
    :url    (format "%s/fileops/create_folder" dbox-url-api) 
    :method "POST"}
  :delete {
    :url    (format "%s/fileops/delete" dbox-url-api) 
    :method "POST"}
  :move {
    :url    (format "%s/fileops/move" dbox-url-api) 
    :method "POST"}

  :permanently_delete {
    :url    (format "%s/fileops/permanently_delete" dbox-url-api-2) 
    :method "POST"}

  :files_get {
    :url    (format "%s/files/auto/" dbox-url-content)
    :method "GET"}
  :files_put {
    :url    (format "%s/files_put/auto/" dbox-url-content)
    :method "POST"
    :params #{:locale :overwrite :parent_rev :autorename}}
})

(def ^:dynamic *access-token*)

(defn api-call-valid?
  "Verify the provided action keyword and optional parameter keywords are valid.

   @return is-valid? true if valid, nil if not valid"
  [action-kw & [param-vec]]
  (and
    (keyword? action-kw)
    (contains? (set (keys api-meta-map)) action-kw) 
    (if-let [valid-param-set (get-in api-meta-map [action-kw :params])]
      (if-not (empty? param-vec)
        (reduce
          (fn anon-valid? [accum elem]
            (if-not (contains? valid-param-set elem) (reduced false) true))
          param-vec))
         true)))


(defmacro with-credentials
  [access-token & body]
  `(binding [*access-token* ~access-token]
    ~@body))


(defn api-action-kw->api-call-meta
  [action-kw]
  (action-kw api-meta-map))


(defn access-token->oauth2-opt
  "Prepare a oauth2 access token as an HTTP option"
  [access-token]
  {:oauth2
    {:access-token  access-token
     :token-type    "bearer"}})


(defn access-token-valid?
  "Validates the supplied access token."
  [access-token]
  (if (empty? access-token) false true))


(defn ->invalid-access-token-exception
  "Throws an invalid access token exception."
  []
  (log/warn "Unable to process API call without an access-token")
  (throw (java.lang.IllegalArgumentException. "API call cannot be processed without an access-token")))


(defn resolve-access-token
  "Retrieves the [optionally] supplied access token or the dynamically scoped access token value,
   with the supplied value taking precedence."
  [& [access-token]]
  (or access-token *access-token*))


(defn resolve-and-validate-access-token
  "Retrieves and validates the [optionally] supplied access token or the dynamically scoped access token value,
   with the supplied value taking precedence."
  [& [access-token]]
  (let [access-token (resolve-access-token access-token)]
    (when-not (access-token-valid? access-token)
      (->invalid-access-token-exception))
    access-token))


(defn api-action-kw->api-call-result
  "Access the web API, using a provided action keyword (@see api-meta-map) to lookup API metadata,
   with the supplied options."
  [action-kw & [{:keys [url-suffix http-opt-map access-token]}]]
  (let [access-token (resolve-and-validate-access-token access-token)]
    (let [api-meta-map  (api-action-kw->api-call-meta action-kw)
          url           (str (:url api-meta-map) (when url-suffix (url/url-encode url-suffix)))]
      (case (string/lower-case (:method api-meta-map))
        "get"
          (oauth2/get   url (merge http-opt-map (access-token->oauth2-opt access-token)))
        "post"
          (oauth2/post  url (merge http-opt-map (access-token->oauth2-opt access-token)))))))


(defn dbox-file->file-stream
  "Wrapper function for downloading a file.

  @return file-stream Returns the downloaded file as a file stream."
  [file-path & [{:keys [access-token]}]]
  (let [access-token (resolve-and-validate-access-token access-token)]
    (:body
      (api-action-kw->api-call-result :files_get {
        :url-suffix   file-path
        :http-opt-map {:as :stream}
        :access-token access-token}))))


(defn file-path->file-stream
  [file-path]
  (if-let [file-obj (jio/file file-path)]
    (jio/input-stream file-obj)))


(defn file-stream->dbox-file
  "Wrapper function for uploading a file.

  @param    file-stream   A file-stream for the file to be uploaded.
  @param    remote-path   A remote / destination path.
  @param    opt-map       A map of options"
  [file-stream remote-path & [{:keys [access-token param-map]}]]
  (let [access-token    (resolve-and-validate-access-token access-token)]
    (api-action-kw->api-call-result :files_put {
      :url-suffix remote-path
      :http-opt-map {
        :query-params param-map
        :body         file-stream
        :as           :stream}
      :access-token access-token})))


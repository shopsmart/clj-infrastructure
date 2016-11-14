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

(def api-meta-map {
  :authorize {
    :url    "https://www.dropboxapi.com/1/oauth/authorize"
    :method "GET"
  }

  :request-token {
    :url    "https://api.dropboxapi.com/1/oauth/request_token"
    :method "POST"
  }
  :access_token {
    :url    "https://api.dropboxapi.com/1/oauth/access_token" 
    :method "POST"
  }
  :token {
    :url    "https://api.dropboxapi.com/1/oauth2/token" 
    :method "POST"
  }
  :token_from_oauth1 {
    :url    "https://api.dropboxapi.com/1/oauth2/token_from_oauth1" 
    :method "POST"
  }
  :disable_access_token {
    :url    "https://api.dropboxapi.com/1/disable_access_token" 
    :method "POST"
  }
  :info {
    :url    "https://api.dropboxapi.com/1/account/info" 
    :method "GET"
  }
  :metadata {
    :url    "https://api.dropboxapi.com/1/metadata/auto"
    :method "GET"
  }
  :link {
    :url    "https://api.dropbox.com/1/metadata/link" 
    :method "POST"
  }
  :delta {
    :url    "https://api.dropboxapi.com/1/delta" 
    :method "POST"
  }
  :latest_cursor {
    :url    "https://api.dropboxapi.com/1/delta/latest_cursor" 
    :method "POST"
  }
  :revisions {
    :url    "https://api.dropboxapi.com/1/revisions/auto"
    :method "GET"
  }
  :restore {
    :url    "https://api.dropboxapi.com/1/restore/auto"
    :method "POST"
  }
  :search {
    :url    "https://api.dropboxapi.com/1/search/auto"
    :method "GET"
  }
  :shares {
    :url    "https://api.dropboxapi.com/1/shares/auto"
    :method "POST"
  }
  :media {
    :url    "https://api.dropboxapi.com/1/media/auto"
    :method "POST"
  }
  :copy_ref {
    :url    "https://api.dropboxapi.com/1/copy_ref/auto"
    :method "GET"
  }
  :shared_folders {
    :url    "https://api.dropboxapi.com/1/shared_folders"
    :method "GET"
  }
  :save_url {
    :url    "https://api.dropboxapi.com/1/save_url/auto"
    :method "POST"
  }
  :save_url_job {
    :url    "https://api.dropboxapi.com/1/save_url_job"
    :method "GET"
  }
  :copy {
    :url    "https://api.dropboxapi.com/1/fileops/copy" 
    :method "POST"
  }
  :create_folder {
    :url    "https://api.dropboxapi.com/1/fileops/create_folder" 
    :method "POST"
  }
  :delete {
    :url    "https://api.dropboxapi.com/1/fileops/delete" 
    :method "POST"
  }
  :move {
    :url    "https://api.dropboxapi.com/1/fileops/move" 
    :method "POST"
  }
  :permanently_delete {
    :url    "https://api.dropbox.com/1/fileops/permanently_delete" 
    :method "POST"
  }
  :files_get {
    :url    "https://content.dropboxapi.com/1/files/auto/"
    :method "GET"
  }
  :files_put {
    :url    "https://content.dropboxapi.com/1/files_put/auto/"
    :method "POST"
    :params #{:locale :overwrite :parent_rev :autorename}
  }
})

(defn api-call-valid?
  "Verify if the provided action keyword and optional parameter keywords are valid.

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


(def ^:dynamic *access-token*)


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
  "Retrieves the [optionally] supplied access token or the lexically scoped access token value,
   with the supplied value taking precedence."
  [& [access-token]]
  (or access-token *access-token*))


(defn resolve-and-validate-access-token
  "Retrieves and validates the [optionally] supplied access token or the lexically scoped access token value,
   with the supplied value taking precedence."
  [& [access-token]]
  (let [access-token (resolve-access-token access-token)]
    (when-not (access-token-valid? access-token)
      (->invalid-access-token-exception))
    access-token))


(defn api-action-kw->api-call-result
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
  [file-stream remote-path & [{:keys [access-token param-map]}]]
  (let [access-token    (resolve-and-validate-access-token access-token)]
    (api-action-kw->api-call-result :files_put {
      :url-suffix remote-path
      :http-opt-map {
        :query-params param-map
        :body         file-stream
        :as           :stream}
      :access-token access-token})))


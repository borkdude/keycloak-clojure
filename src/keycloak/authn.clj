(ns keycloak.authn
  (:require
   [clojure.tools.logging :as log :refer [info]]
   [clj-http.client :as http]
   [cheshire.core :refer [parse-string]]
   [keycloak.deployment :as deployment]))

;(set! *warn-on-reflection* true)

(defn oidc-connect-url [auth-server-url realm-name]
  (str auth-server-url "/realms/" realm-name "/protocol/openid-connect/token"))

(defn client-credentials
  ([client-id username password]
   {:grant_type "password"
    :client_id client-id
    :username username
    :password password})
  ([client-id client-secret]
   {:grant_type "client_credentials"
    :client_id client-id
    :client_secret client-secret}))

(defn authenticate
  "Return the bearer token decoded as a clojure map with `:access_token` and `:refresh_token` keys, beware underscore `_` not hyphen `-`.
  The keycloak conf needs the `:auth-server-url`, `realm` and `client-id` keys"
  ([{:keys [auth-server-url realm client-id] :as conf} client-secret]
   (authenticate auth-server-url realm client-id client-secret))
  ([{:keys [auth-server-url realm client-id] :as conf} username password]
   (authenticate auth-server-url realm client-id username password))
  ([auth-server-url realm client-id client-secret]
   (info "Authenticate against" (oidc-connect-url auth-server-url realm) "for client-id"  client-id)
   (-> (http/post (oidc-connect-url auth-server-url realm)
                  {:form-params (client-credentials client-id client-secret) :content-type "application/x-www-form-urlencoded"})
       :body
       (parse-string true)))
  ([auth-server-url realm client-id username password]
   (info "Authenticate against" (oidc-connect-url auth-server-url realm) "for client-id"  client-id "with user" username)
   (-> (http/post (oidc-connect-url auth-server-url realm)
                  {:form-params (client-credentials client-id username password) :content-type "application/x-www-form-urlencoded"})
       :body
       (parse-string true))))


(defn access-token [^org.keycloak.authorization.client.AuthzClient client username password]
  (-> client (.obtainAccessToken username password)))

(defn auth-cookie [bearer]
  {"X-Authorization-Token" {:discard true, :path "/", :value (:access_token bearer), :version 0}})

(defn auth-header
  "Return a map with \"authorization\" key and value the access token with \"Bearer \" prefix. Argument is the data structure returned by the `keycloak.authn/authenticate` function"
  [bearer]
  {"authorization" (str "Bearer " (:access_token bearer))})

(ns keycloak.deployment
  (:require [clojure.tools.logging :as log :refer [info error]]
            [cheshire.core :as json :refer [encode]]
            [clojure.java.io :as io :refer [input-stream]]
            [keycloak.admin :refer [get-client-secret]])
  (:import [java.util.concurrent TimeUnit]
           [org.keycloak.adapters KeycloakDeployment KeycloakDeploymentBuilder]
           [org.keycloak.admin.client KeycloakBuilder]
           [org.keycloak RSATokenVerifier OAuth2Constants]
           [org.jboss.resteasy.client.jaxrs ResteasyClientBuilder]))

;(set! *warn-on-reflection* true)

(defn deployment
  "take a keycloak client configuration as EDN and return a KeycloakDeployment object, see [[client-conf]] for getting a proper conf structure"
  ^org.keycloak.adapters.KeycloakDeployment [client-conf]
  (try
    (let [keycloak-json-is (io/input-stream (.getBytes (json/encode client-conf)))
          truncated-secret (when-let [secret (get-in client-conf [:credentials :secret])] (subs secret 0 8))]
      (info (format "Build keycloak deployment for realm %s on server %s secret starting with %s" (:realm client-conf) (:auth-server-url client-conf) truncated-secret))
      (KeycloakDeploymentBuilder/build keycloak-json-is))
    (catch java.lang.Throwable t
      (throw (ex-info "Failed to build the keycloak app client" client-conf t)))))

(defn client-conf
  "returns a keycloak client configuration data structure given the params"
  ([{:keys [realm auth-server-url client-id client-secret] :as conf}]
   (client-conf auth-server-url realm client-id client-secret))
  ([auth-server-url realm-name client-id]
   (client-conf auth-server-url realm-name client-id nil))
  ([auth-server-url realm-name client-id client-secret]
   (->> {:realm realm-name
         :auth-server-url auth-server-url
         :credentials {:secret client-secret}
         :ssl-required "external"
         :verify-token-audience true
         :use-resource-role-mappings true
         :resource client-id
         :confidential-port 0
         :policy-enforcer {}}
        (#(if client-secret (assoc % :credentials {:secret client-secret}) %)))))

(defn client-conf-input-stream
  "return the keycloak client config as an input stream containing JSON, see [[client-conf]]"
  ([auth-server-url realm-name client-id]
   (client-conf-input-stream auth-server-url realm-name client-id nil))
  ([auth-server-url realm-name client-id client-secret]
   (io/input-stream (.getBytes (json/encode (client-conf auth-server-url realm-name client-id client-secret))))))

(def REST_CONNECTION_POOL_SIZE 8)
(def REST_CONNECT_TIMEOUT_SECONDS 4)
(def REST_READ_TIMEOUT_SECONDS 20)

(defn- base-keycloak-builder
  "returns a org.keycloak.admin.client.KeycloakBuilder with a RestEasy http client (connection pool size of 4) given a [[client-conf]] param"
  ^org.keycloak.admin.client.KeycloakBuilder [client-conf]
  (-> (KeycloakBuilder/builder)
      (.realm (:realm client-conf))
      (.serverUrl (:auth-server-url client-conf))
      (.clientId (:resource client-conf))
      (.resteasyClient (-> (ResteasyClientBuilder.)
                           (.connectionPoolSize REST_CONNECTION_POOL_SIZE)
                           (.connectTimeout REST_CONNECT_TIMEOUT_SECONDS TimeUnit/SECONDS)
                           (.readTimeout REST_READ_TIMEOUT_SECONDS TimeUnit/SECONDS)
                           (.build)))))

(defn keycloak-client
  "Build a [org.keycloak.admin.client.Keycloak](https://www.keycloak.org/docs-api/12.0/javadocs/org/keycloak/admin/client/Keycloak.html) object from a [[client-conf]] and a credential (secret or username/password), use the RestEasy client.
  This keycloak-client object will be used as the first param for every interactions with the Keycloak server.
  "
  (^org.keycloak.admin.client.Keycloak [conf secret]
   (info "Build keycloak client with config for realm" (:realm conf) "on server" (:auth-server-url conf) "with secret starting with" (when-let [secret  (get-in conf [:credentials :secret])] (subs secret 0 8)))
   (-> (base-keycloak-builder conf)
       (.clientSecret secret)
       (.grantType OAuth2Constants/CLIENT_CREDENTIALS)
       (.build)))
  (^org.keycloak.admin.client.Keycloak [conf username password]
   (info "Build keycloak client with config for realm" (:realm conf) "on server" (:auth-server-url conf) "with username" username)
   (-> (base-keycloak-builder conf)
       (.username username)
       (.password password)
       (.grantType OAuth2Constants/PASSWORD)
       (.build))))

(defn deployment-for-realm [^org.keycloak.admin.client.Keycloak keycloak-client auth-server-url client-id realm-name]
  (info (format "Get client secret on server %s for realm %s and client \"%s\"" auth-server-url realm-name client-id))
  (try
    (let [client-secret (get-client-secret keycloak-client realm-name client-id)]
      (deployment (client-conf auth-server-url realm-name client-id client-secret)))
    (catch javax.ws.rs.NotFoundException nfe
      (error (str "The client '"client-id "' was not found in realm '" realm-name "', maybe you should create it at the repl with: (fill! \""realm-name"\")"))
      nil)))

(defn deployment-for-realms
  "retrieve the secrets and build dynamically a map with realm-name as key and the keycloak deployment as value given a keycloak client with admin role, an array of realm name. This is useful for large number of realms and multi-tenant applications or tests, otherwise you should define them statically"
  [^org.keycloak.admin.client.Keycloak keycloak-client auth-server-url client-id realms-name]
  (into {} (map (fn [realm-name]
                  [realm-name (deployment-for-realm keycloak-client auth-server-url client-id realm-name)]) realms-name)))

(defn verify
  "Verify an Access Token given a deployment to check against"
  (^org.keycloak.representations.AccessToken [^KeycloakDeployment deployment ^java.lang.String token]
   (let [realm-info-url (.getRealmInfoUrl deployment)
         ;;TODO replace RSATokenVerifier with TokenVerifier (see https://stackoverflow.com/questions/57881996/keycloak-core-deprecate-class-rsatokenverifier-then-what-is-the-replacement)
         verifier       (-> token RSATokenVerifier/create (.realmUrl realm-info-url))
         kid            (-> verifier (.getHeader) (.getKeyId))
         public-key     (.getPublicKey (.getPublicKeyLocator deployment) kid deployment)]
     (-> verifier (.publicKey public-key) (.verify) (.getToken))))
  (^org.keycloak.representations.AccessToken [^KeycloakDeployment deployments ^java.lang.String realm-name ^java.lang.String token]
   (when (not (coll? deployments))
     (throw (ex-info "deployments argument must be a coll of KeycloakDeployment object" {:deployments deployments})))
   (when (not (contains? deployments realm-name))
     (throw (ex-info (format "Deployments collection doesn't contain a deployment for realm %s" realm-name) {:realm-name realm-name :deployments deployments})))
   (verify (get deployments realm-name) token)))

(defrecord ClojureAccessToken
    [username roles nonce auth-time session-state access-token-hash code-hash name given-name family-name middle-name
     nick-name preferred-username profile picture website email email-verified gender birthdate zoneinfo
     locale phone-number phone-number-verified address updated-at claims-locales acr state-hash other-claims]
  Object
  (toString [access-token] (pr-str access-token)))

(defn extract
  "Return a [[keycloak.deployment/ClojureAccessToken]] record with `:user` and `:roles` keys with values extracted from the Keycloak access token along with all the props of the AccessToken bean"
  ^keycloak.deployment.ClojureAccessToken [^org.keycloak.representations.AccessToken access-token]
  (map->ClojureAccessToken {:username              (.getPreferredUsername access-token)
                            :roles                 (set (map keyword (.getRoles (.getRealmAccess access-token))))
                            :nonce                 (.getNonce access-token)
                            :auth-time             (.getAuthTime access-token)
                            :session-state         (.getSessionState access-token)
                            :access-token-hash     (.getAccessTokenHash access-token)
                            :code-hash             (.getCodeHash access-token)
                            :name                  (.getName access-token)
                            :given-name            (.getGivenName access-token)
                            :family-name           (.getFamilyName access-token)
                            :middle-name           (.getMiddleName access-token)
                            :nick-name             (.getNickName access-token)
                            :preferred-username    (.getPreferredUsername access-token)
                            :profile               (.getProfile access-token)
                            :picture               (.getPicture access-token)
                            :website               (.getWebsite access-token)
                            :email                 (.getEmail access-token)
                            :email-verified        (.getEmailVerified access-token)
                            :gender                (.getGender access-token)
                            :birthdate             (.getBirthdate access-token)
                            :zoneinfo              (.getZoneinfo access-token)
                            :locale                (.getLocale access-token)
                            :phone-number          (.getPhoneNumber access-token)
                            :phone-number-verified (.getPhoneNumberVerified access-token)
                            :address               (.getAddress access-token)
                            :updated-at            (.getUpdatedAt access-token)
                            :claims-locales        (.getClaimsLocales access-token)
                            :acr                   (.getAcr access-token)
                            :state-hash            (.getStateHash access-token)
                            :other-claims          (into {} (map (fn [[k v]] [(keyword k) v]) (.getOtherClaims access-token)))}))


(defn access-token
  "Get an access token extracted in a [[ClojureAccessToken]] record with one additionnal attribute `:token` that hold the token as a string"
  [deployment ^org.keycloak.admin.client.Keycloak keycloak-client]
  (let [access-token-string (-> keycloak-client (.tokenManager) (.getAccessToken) (.getToken))
        access-token (->> access-token-string (verify deployment) (extract))]
    (assoc access-token :token access-token-string)))

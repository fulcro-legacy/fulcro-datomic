(ns seeddata.auth
  (:require [fulcro.datomic.test-helpers :as s]
            [datomic.api :as d])
  (:import java.util.UUID))

(defn gen-realm [realmpk accountid accountname userids]
  (s/generate-entity {
                      :db/id              realmpk
                      :realm/account-id   accountid
                      :realm/account-name accountname
                      :realm/user         userids}))
(defn gen-user [userid username password email is-active validation-code]
  (s/generate-entity {
                      :db/id                userid
                      :user/user-id         username
                      :user/password        "some encrypted data"
                      :user/email           email
                      :user/is-active       is-active
                      :user/validation-code validation-code}))
(defn gen-application [pk app-id name component]
  (s/generate-entity {
                      :db/id                      pk
                      :application/application-id app-id
                      :application/name           name
                      :application/component      component}))
(defn gen-software-entitlement [sw-entitlement-pk kind permission]
  (s/generate-entity {
                      :db/id                     sw-entitlement-pk
                      :software-entitlement/kind kind
                      :entitlement/permission    permission}))

(defn gen-property-group [pk name properties]
  (s/generate-entity {
                      :db/id                   pk
                      :property-group/name     name
                      :property-group/property properties}))

(defn gen-property-entitlement
  [prop-entitlement-pk kind permission]
  (s/generate-entity {
                      :db/id                     prop-entitlement-pk
                      :property-entitlement/kind kind
                      :entitlement/permission    permission}))
(defn gen-authorization-role [auth-role-pk name]
  (s/generate-entity {
                      :db/id                   auth-role-pk
                      :authorization-role/name name}))
(defn gen-component [component-pk name]
  (s/generate-entity {
                      :db/id          component-pk
                      :component/name name}))
(defn gen-subscription [subscription-pk name]
  (s/generate-entity {
                      :db/id             subscription-pk
                      :subscription/name name}))

(defn create-base-user-and-realm
  "Creates a vector containing two users and an realm. Their temporary IDs are:

  :datomic.id/realm1
  :datomic.id/user1
  :datomic.id/user2
  :datomic.id/user3

  You can conjoin this data with your own, and then call seed/link-entities to
  resolve these fake temporary IDs to real ones before transacting them into the
  database.
  "
  []
  (let [user1 (gen-user :datomic.id/user1 (d/squuid) "letmein" "user1@example.net" true "abc")
        user2 (gen-user :datomic.id/user2 (d/squuid) "letmein" "user2@example.net" true "abc")
        user3 (gen-user :datomic.id/user3 (d/squuid) "letmein" "user3@example.net" true "abc")
        realm (gen-realm :datomic.id/realm1 "realm1" "Realm" [:datomic.id/user1 :datomic.id/user2])]
    [user1 user2 user3 realm]))

(defn create-oauth-base-user-and-realm
  "Creates a vector containing two users and an realm. Their temporary IDs are:

  :datomic.id/realm1
  :datomic.id/user1
  :datomic.id/user2

  You can conjoin this data with your own, and then call seed/link-entities to
  resolve these fake temporary IDs to real ones before transacting them into the
  database.
  "
  []
  (let [user1 (gen-user :datomic.id/user1 (UUID/fromString "5584ada2-5e61-41de-bd99-cdef32a83f15") "letmein" "user1@example.net" true "abc")
        user2 (gen-user :datomic.id/user2 (UUID/fromString "5584ada2-5e61-41de-bd99-cdef32a83f16") "letmein" "user2@example.net" true "abc")
        realm (gen-realm :datomic.id/realm1 "realm1" "realm" [:datomic.id/user1 :datomic.id/user2])]
    [user1 user2 realm]))


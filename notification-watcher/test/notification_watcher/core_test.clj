(ns notification-watcher.core-test
  (:require [clojure.test :refer :all]
            [notification-watcher.core :refer :all]
            [clj-http.fake :as fake]))

(deftest fetch-templates-logic-test
  (testing "fetch-templates in mock mode (MOCK_MODE=true)"
    (with-redefs [notification-watcher.core/mock-mode? true]
      (let [templates (fetch-templates "test-app-id" "test-token")]
        (is (= templates mock-templates-com-mudanca))
        (is (string? (with-out-str (fetch-templates "test-app-id" "test-token")))) ; Check for print output
        ))))

  (testing "fetch-templates with successful API call"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"status\":\"success\",\"templates\":[{\"elementName\":\"tpl1\",\"category\":\"MARKETING\"}]}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (= [{:elementName "tpl1" :category "MARKETING"}] templates))
          (is (some? (re-find #"\[WORKER\] Resposta da API Gupshup \(status 200 OK\). Corpo:" (with-out-str (fetch-templates "test-app-id" "test-token")))))))))


  (testing "fetch-templates with API returning non-200 status"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 401 :headers {} :body "{\"status\":\"error\",\"message\":\"Unauthorized\"}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (nil? templates))
          (is (some? (re-find #"\[WORKER\] Erro ao buscar templates da Gupshup. Status HTTP: 401" (with-out-str (fetch-templates "test-app-id" "test-token")))))))))


  (testing "fetch-templates with API returning 200 but unexpected body structure"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"message\":\"Unexpected success format\"}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (= [] templates)) ; Expecting empty list due to get-in default
          (is (some? (re-find #"\[WORKER\] Resposta da API Gupshup \(status 200 OK\). Corpo:" (with-out-str (fetch-templates "test-app-id" "test-token")))))))))

  (testing "fetch-templates with network error (exception using fake/with-fake-routes)"
    (with-redefs [notification-watcher.core/mock-mode? false] ; Keep mock-mode false for this
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] (throw (java.net.ConnectException. "Connection refused by fake route")))}
        (let [templates (fetch-templates "test-app-id" "test-token")
              output (with-out-str (fetch-templates "test-app-id" "test-token"))]
          (is (nil? templates))
          (is (some? (re-find #"!!!! \[WORKER\] Exceção CRÍTICA ao conectar com a API Gupshup ou processar resposta !!!!" output)))
          (is (some? (re-find #"Tipo da exceção: class java.net.ConnectException" output)))
          (is (some? (re-find #"Mensagem: Connection refused by fake route" output)))))))

  (testing "fetch-templates with successful API call and empty templates list"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"status\":\"success\",\"templates\":[]}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (= [] templates))
          (is (some? (re-find #"\[WORKER\] Resposta da API Gupshup \(status 200 OK\). Corpo:" (with-out-str (fetch-templates "test-app-id" "test-token")))))))))

  (testing "fetch-templates with API returning 200 but malformed JSON"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"status\":\"success\",\"templates\":[{\"elementName\":\"tpl1\""})} ; Malformed JSON
        (let [templates (fetch-templates "test-app-id" "test-token")
              output (with-out-str (fetch-templates "test-app-id" "test-token"))]
          ;; Depending on how cheshire handles malformed JSON with :as :json,
          ;; it might throw an exception caught by the outer try-catch, or return nil from parsing.
          ;; The current code's catch block for client/get would handle exceptions from cheshire.
          ;; If cheshire returns nil or an empty map on error without throwing, the (get-in (:body response) ...) would yield [].
          ;; Let's assume it throws and gets caught by the main catch.
           (is (nil? templates))
           (is (some? (re-find #"!!!! \[WORKER\] Exceção CRÍTICA ao conectar com a API Gupshup ou processar resposta !!!!" output)))))))

(deftest check-for-changes-test
  (testing "check-for-changes with templates having category changes"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl1", :category "MARKETING", :oldCategory "UTILITY", :status "ACTIVE"}
                                     {:id "2", :elementName "tpl2", :category "UTILITY", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 1 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl1" output)))
        (is (some? (re-find #"Categoria Antiga: UTILITY" output)))
        (is (some? (re-find #"Nova Categoria: MARKETING" output))))))

  (testing "check-for-changes with no templates having category changes"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl1", :category "MARKETING", :status "ACTIVE"}
                                     {:id "2", :elementName "tpl2", :category "UTILITY", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Nenhum template ativo com mudança de categoria encontrado." output))))))

  (testing "check-for-changes with a mix of templates, including FAILED status"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl1", :category "MARKETING", :oldCategory "UTILITY", :status "ACTIVE"}
                                     {:id "2", :elementName "tpl2", :category "UTILITY", :status "FAILED"} ; Should be ignored
                                     {:id "3", :elementName "tpl3", :category "AUTHENTICATION", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates recebidos da API: 3." output)))
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED\) sendo processados: 2." output)))
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 1 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl1" output))))))

  (testing "check-for-changes with an empty list of templates from fetch-templates"
    (with-redefs [fetch-templates (fn [app-id token] [])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates recebidos da API: 0." output)))
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED\) sendo processados: 0." output)))
        (is (some? (re-find #"\[WORKER\] Nenhum template ativo com mudança de categoria encontrado." output))))))

  (testing "check-for-changes when fetch-templates returns nil (simulating API error)"
    (with-redefs [fetch-templates (fn [app-id token] nil)]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Não foi possível obter a lista de templates para verificação." output)))))))

(deftest log-category-change-test
  (testing "log-category-change correctly logs the details"
    (let [template {:id "template123"
                    :elementName "MyTestTemplate"
                    :oldCategory "UTILITY"
                    :category "MARKETING"}
          output (with-out-str (log-category-change template))]
      (is (some? (re-find #"\[WORKER\] Mudança de categoria detectada para o template:" output)))
      (is (some? (re-find #"ID: template123" output)))
      (is (some? (re-find #"Nome: MyTestTemplate" output)))
      (is (some? (re-find #"Categoria Antiga: UTILITY" output)))
      (is (some? (re-find #"Nova Categoria: MARKETING" output))))))

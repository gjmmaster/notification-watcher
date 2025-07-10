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
          (is (nil? templates))
          (is (some? (re-find #"!!!! \[WORKER\] Exceção CRÍTICA ao conectar com a API Gupshup ou processar resposta !!!!" output)))))))

  (testing "fetch-templates with API returning 200 OK but templates field is null"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"status\":\"success\",\"templates\":null}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")]
          (is (= [] templates)) ; Default value from get-in
          (is (some? (re-find #"\[WORKER\] Resposta da API Gupshup \(status 200 OK\). Corpo:" (with-out-str (fetch-templates "test-app-id" "test-token")))))))))

  (testing "fetch-templates with API returning 200 OK but templates array contains non-map elements"
    (with-redefs [notification-watcher.core/mock-mode? false]
      (fake/with-fake-routes
        {"https://api.gupshup.io/sm/api/v1/template/list/test-app-id"
         (fn [request] {:status 200 :headers {} :body "{\"status\":\"success\",\"templates\":[{\"elementName\":\"tpl1\"}, \"string-element\", null, {\"elementName\":\"tpl2\"}]}"})}
        (let [templates (fetch-templates "test-app-id" "test-token")
              output (with-out-str (fetch-templates "test-app-id" "test-token"))]
          (is (= [{:elementName "tpl1"} "string-element" nil {:elementName "tpl2"}] templates))
          (is (some? (re-find #"\[WORKER\] Resposta da API Gupshup \(status 200 OK\)." output)))))))


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
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED, apenas mapas\) sendo processados: 2." output)))
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 1 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl1" output))))))

  (testing "check-for-changes with various template statuses"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl-active-changed", :category "MARKETING", :oldCategory "UTILITY", :status "ACTIVE"}
                                     {:id "2", :elementName "tpl-pending", :category "UTILITY", :status "PENDING"}
                                     {:id "3", :elementName "tpl-rejected-changed", :category "AUTHENTICATION", :oldCategory "UTILITY", :status "REJECTED"}
                                     {:id "4", :elementName "tpl-failed", :category "MARKETING", :status "FAILED"}
                                     {:id "5", :elementName "tpl-active-no-change", :category "UTILITY", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates recebidos da API: 5." output)))
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED, apenas mapas\) sendo processados: 4." output))) ; ACTIVE, PENDING, REJECTED
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 2 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl-active-changed" output)))
        (is (some? (re-find #"Nome: tpl-rejected-changed" output)))
        (is (not (some? (re-find #"Nome: tpl-pending" output)))) ; No change logged
        (is (not (some? (re-find #"Nome: tpl-failed" output))))))) ; Not active, no change logged

  (testing "check-for-changes with active template where oldCategory equals category"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl-no-real-change", :category "MARKETING", :oldCategory "MARKETING", :status "ACTIVE"}
                                     {:id "2", :elementName "tpl-real-change", :category "UTILITY", :oldCategory "MARKETING", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates recebidos da API: 2." output)))
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED, apenas mapas\) sendo processados: 2." output)))
        ;; Current logic logs if oldCategory is present, regardless of actual change
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 2 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl-no-real-change" output)))
        (is (some? (re-find #"Categoria Antiga: MARKETING" output)))
        (is (some? (re-find #"Nova Categoria: MARKETING" output)))
        (is (some? (re-find #"Nome: tpl-real-change" output))))))

  (testing "check-for-changes with active template, oldCategory present, but category missing"
    (with-redefs [fetch-templates (fn [app-id token]
                                    [{:id "1", :elementName "tpl-missing-category", :oldCategory "UTILITY", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates recebidos da API: 1." output)))
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED, apenas mapas\) sendo processados: 1." output)))
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 1 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl-missing-category" output)))
        (is (some? (re-find #"Categoria Antiga: UTILITY" output)))
        (is (some? (re-find #"Nova Categoria: \s*$" output)))))) ; nil becomes empty string in str, check for optional space and end of line

  (testing "check-for-changes with template list containing non-map elements"
    (with-redefs [fetch-templates (fn [app-id token]
                                     [{:id "1", :elementName "tpl1", :category "MARKETING", :oldCategory "UTILITY", :status "ACTIVE"}
                                      "a-string-template" ; Non-map element
                                      nil ; Another non-map element
                                      {:id "2", :elementName "tpl2", :category "UTILITY", :status "ACTIVE"}])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates recebidos da API: 4." output)))
        (is (some? (re-find #"\[WORKER\] Número de itens não-mapa ignorados: 2." output)))
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED, apenas mapas\) sendo processados: 2." output)))
        (is (some? (re-find #"\[WORKER\] Dentre os ativos, 1 templates com mudança de categoria encontrados." output)))
        (is (some? (re-find #"Nome: tpl1" output)))
        (is (not (some? (re-find #"Nome: tpl2" output))))))) ; tpl2 has no oldCategory

  (testing "check-for-changes with an empty list of templates from fetch-templates"
    (with-redefs [fetch-templates (fn [app-id token] [])]
      (let [output (with-out-str (check-for-changes "test-app-id" "test-token"))]
        (is (some? (re-find #"\[WORKER\] Total de templates recebidos da API: 0." output)))
        (is (some? (re-find #"\[WORKER\] Total de templates ativos \(não FAILED, apenas mapas\) sendo processados: 0." output)))
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

(ns notification-watcher.core
  (:require [clj-http.client :as client]
            [cheshire.core :as json]
            [org.httpkit.server :as server]
            [compojure.core :refer [defroutes GET]]
            [compojure.route :as route])
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;               FEATURE FLAG E DADOS DE TESTE                      ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def mock-mode?
  "Verifica a variável de ambiente 'MOCK_MODE'. Se for 'true', ativa o modo de teste."
  (= (System/getenv "MOCK_MODE") "true"))

(def mock-templates-com-mudanca
  "Dados de teste que simulam uma mudança de categoria."
  [{"elementName" "template_normal_1", "wabaId" "111222333", "category" "MARKETING"}
   {"elementName" "template_que_mudou", "wabaId" "444555666", "category" "UTILITY", "oldCategory" "MARKETING"}
   {"elementName" "template_normal_2", "wabaId" "777888999", "category" "AUTHENTICATION"}])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                    FUNÇÕES DE LÓGICA DO WATCHER                  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fetch-templates
  "Busca templates. Usa dados de teste se mock-mode? for true, senão chama a API real."
  [app-id token]
  (if mock-mode?
    ;; --- MODO DE TESTE ---
    (do
      (println "[MODO DE TESTE ATIVADO] Usando dados mockados para a verificação.")
      mock-templates-com-mudanca)

    ;; --- MODO REAL (COM AS ALTERAÇÕES) ---
    (let [;; ALTERAÇÃO 1: A URL foi atualizada para a API Padrão.
          url (str "https://api.gupshup.io/sm/api/v1/template/list/" app-id)]
      (try
        (let [;; ALTERAÇÃO 2: O cabeçalho foi mudado de 'Authorization' para 'apikey',
              ;; que é o padrão para a API Padrão da Gupshup.
              response (client/get url {:headers {:apikey token} ; <-- MUDANÇA AQUI
                                        :as :json
                                        :throw-exceptions false})]
          (if (= (:status response) 200)
            ;; A API padrão geralmente retorna os dados dentro de uma chave "list" ou "templates".
            ;; Estamos pegando a chave "templates" aqui.
            (-> response :body (get "templates"))
            (do
              (println (str "Erro ao buscar templates. Status: " (:status response) " | Body: " (:body response)))
              nil)))
        (catch Exception e
          (println (str "Exceção ao buscar templates: " (.getMessage e)))
          nil)))))

(defn log-change-notification
  "Loga uma notificação de mudança de template no console."
  [template]
  (let [elementName (get template "elementName")
        wabaId      (get template "wabaId")
        oldCategory (get template "oldCategory")
        category    (get template "category")]
    (println "\n--- MUDANÇA DETECTADA ---")
    (println (str "Template: '" elementName "' (WABA ID: " wabaId ")"))
    (println (str "Categoria anterior: " oldCategory))
    (println (str "Nova categoria: " category))
    (println "---------------------------\n")))

(defn check-for-changes
  "Verifica se houve mudanças nos templates."
  [app-id token]
  (if-let [templates (fetch-templates app-id token)]
    (doseq [template templates]
      (when (get template "oldCategory")
        (log-change-notification template)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                    LÓGICA DO SERVIDOR E PONTO DE ENTRADA                 ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- start-watcher-loop!
  "Inicia o loop de verificação em uma thread de fundo (background)."
  []
  (let [app-id (System/getenv "GUPSHUP_APP_ID")
        token (System/getenv "GUPSHUP_TOKEN")]
    (if (and app-id token)
      (future
        (println "Watcher em background iniciado.")
        (loop []
          (println "Executando verificação de templates...")
          (check-for-changes app-id token)
          (Thread/sleep 600000) ; Pausa por 10 minutos
          (recur)))
      (println "ERRO CRÍTICO: Variáveis de ambiente GUPSHUP_APP_ID e GUPSHUP_TOKEN não definidas."))))

(defroutes app-routes
  "Define as rotas da nossa aplicação web."
  (GET "/" [] "Serviço Notification Watcher está no ar.")
  (route/not-found "Página não encontrada."))

(defn -main
  "Ponto de entrada da aplicação."
  [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))]
    (start-watcher-loop!)
    (server/run-server #'app-routes {:port port})
    (println (str "Servidor web iniciado na porta " port ". O watcher está rodando em background."))))

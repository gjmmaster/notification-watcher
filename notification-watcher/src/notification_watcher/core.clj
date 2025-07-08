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
  [{"elementName" "template_que_mudou", "wabaId" "444555666", "category" "UTILITY", "oldCategory" "MARKETING"}])


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
      
    ;; --- MODO REAL ---
    (let [url (str "https://api.gupshup.io/sm/api/v1/template/list/" app-id)] ; URL CORRETA
      (try
        (let [response (client/get url {;; --- CORREÇÃO APLICADA AQUI ---
                                        :headers {"apikey" token} ; O nome do header foi corrigido
                                        :as :json
                                        :throw-exceptions false})]
          (if (= (:status response) 200)
            (-> response :body (get "templates"))
            (do
              (println (str "Erro ao buscar templates. Status: " (:status response)))
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

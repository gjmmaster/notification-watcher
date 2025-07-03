(ns notification-watcher.core
  (:require [clj-http.client :as client]
            [cheshire.core :as json]
            [org.httpkit.server :as server]
            [compojure.core :refer [defroutes GET]]
            [compojure.route :as route])
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                                                                ;;
;;     LÓGICA DO WATCHER (NENHUMA MUDANÇA FUNCIONAL AQUI)         ;;
;;                                                                ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fetch-templates
  "Busca templates da API Gupshup."
  [app-id token]
  (let [url (str "https://partner.gupshup.io/partner/app/" app-id "/templates")]
    (try
      (let [response (client/get url {:headers {:Authorization token}
                                      :as :json
                                      :throw-exceptions false})]
        (if (= (:status response) 200)
          (-> response :body (get "templates"))
          (do
            (println (str "Erro ao buscar templates. Status: " (:status response)))
            nil)))
      (catch Exception e
        (println (str "Exceção ao buscar templates: " (.getMessage e)))
        nil))))

(defn log-change-notification
  "Loga uma notificação de mudança de template no console."
  [template]
  (let [elementName (get template "elementName")
        wabaId (get template "wabaId")
        oldCategory (get template "oldCategory")
        category (get template "category")]
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
;;                                                                        ;;
;;     NOVA LÓGICA: SERVIDOR WEB E TAREFA EM BACKGROUND                   ;;
;;                                                                        ;;
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
  ;; Esta rota principal é para o UptimeRobot nos manter "vivos".
  (GET "/" [] "Serviço Notification Watcher está no ar.")

  ;; Uma rota para qualquer outro acesso que não seja a principal.
  (route/not-found "Página não encontrada."))

(defn -main
  "Ponto de entrada da aplicação."
  [& args]
  ;; O Render define a variável de ambiente PORT. Usamos 8080 para rodar localmente.
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))]
    
    ;; 1. Inicia a tarefa do watcher em background
    (start-watcher-loop!)
    
    ;; 2. Inicia o servidor web para responder aos pings
    (server/run-server #'app-routes {:port port})
    
    (println (str "Servidor web iniciado na porta " port ". O watcher está rodando em background."))))

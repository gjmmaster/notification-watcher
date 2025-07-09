(ns notification-watcher.core
  (:require [clj-http.client :as client]
            [org.httpkit.server :as server]
            [clojure.pprint :as pprint])
  (:gen-class)) ; <-- O erro estava aqui. Esta linha deve estar dentro do ns.

;; CORREÇÃO - A declaração do namespace (ns) deve ser assim:
(ns notification-watcher.core
  (:require [clj-http.client :as client]
            [org.httpkit.server :as server]
            [clojure.pprint :as pprint])
  (:gen-class))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;  1. FEATURE FLAG E DADOS DE TESTE (LÓGICA ORIGINAL)                        ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def mock-mode?
  (= (System/getenv "MOCK_MODE") "true"))

(def mock-templates-com-mudanca
  [{"elementName" "template_normal_1", "wabaId" "111222333", "category" "MARKETING"}
   {"elementName" "template_que_mudou", "wabaId" "444555666", "category" "UTILITY", "oldCategory" "MARKETING"}
   {"elementName" "template_normal_2", "wabaId" "777888999", "category" "AUTHENTICATION"}])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;  2. FUNÇÕES DE LÓGICA DO WATCHER (COM LOG DA RESPOSTA BRUTA)               ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fetch-templates
  "Busca templates. Usa dados de teste ou chama a API real e LOGA A RESPOSTA BRUTA."
  [app-id token]
  (if mock-mode?
    (do
      (println "[MODO DE TESTE ATIVADO] Usando dados mockados.")
      mock-templates-com-mudanca)

    (let [url (str "https://api.gupshup.io/sm/api/v1/template/list/" app-id)]
      (println (str "[WORKER] Tentando conexão com a API em: " url))
      (try
        (let [response (client/get url {:headers          {:apikey token}
                                        :as               :json
                                        :throw-exceptions false
                                        :conn-timeout     15000
                                        :socket-timeout   15000})]

          (println "[WORKER] RESPOSTA BRUTA RECEBIDA DA GUPSHUP:")
          (pprint/pprint (:body response))
          (println "=========================================================")

          (if (= (:status response) 200)
            (get-in (:body response) [:templates] [])
            (do
              (println (str "[WORKER] Erro ao buscar templates. Status: " (:status response)))
              nil)))
        (catch Exception e
          (println "\n!!!! [WORKER] Exceção CRÍTICA ao conectar com a API !!!!")
          (.printStackTrace e)
          (println "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n")
          nil)))))

(defn log-change-notification
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
  [app-id token]
  (println "[WORKER] Executando verificação de templates...")
  (if-let [templates (fetch-templates app-id token)]
    (do
      (println (str "[WORKER] " (count templates) " templates recebidos. Procurando por mudanças..."))
      (doseq [template templates]
        (when (get template "oldCategory")
          (log-change-notification template))))
    (println "[WORKER] Não foi possível obter a lista de templates para verificação.")))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;  3. LÓGICA DO SERVIDOR E PONTO DE ENTRADA                                  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- start-watcher-loop!
  []
  (let [app-id (System/getenv "GUPSHUP_APP_ID")
        token  (System/getenv "GUPSHUP_TOKEN")]
    (if (and app-id token)
      (future
        (println "[WORKER] Watcher em background iniciado.")
        (loop []
          (check-for-changes app-id token)
          (println "[WORKER] Verificação concluída. Próximo ciclo em 10 minutos.")
          (Thread/sleep 600000)
          (recur)))
      (println "ERRO CRÍTICO: Variáveis de ambiente não definidas."))))

(defn app-handler
  [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Serviço Notification Watcher está no ar."})

(defn -main
  [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))]
    (println (str "[SERVER] Iniciando servidor web na porta " port "."))
    (start-watcher-loop!)
    (server/run-server #'app-handler {:port port})
    (println "[SERVER] Servidor iniciado. O watcher está rodando em background.")))

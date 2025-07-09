(ns notification-watcher.core
  (:require [clj-http.client :as client]
            [org.httpkit.server :as server])
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;  1. FEATURE FLAG E DADOS DE TESTE (LÓGICA RESTAURADA)                       ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def mock-mode?
  "Verifica a variável de ambiente 'MOCK_MODE'. Se for 'true', ativa o modo de teste."
  (= (System/getenv "MOCK_MODE") "true"))

(def mock-templates-com-mudanca
  "Dados de teste que simulam uma mudança de categoria. As chaves são strings
   para simular perfeitamente a resposta da API."
  [{"elementName" "template_normal_1", "wabaId" "111222333", "category" "MARKETING"}
   {"elementName" "template_que_mudou", "wabaId" "444555666", "category" "UTILITY", "oldCategory" "MARKETING"}
   {"elementName" "template_normal_2", "wabaId" "777888999", "category" "AUTHENTICATION"}])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;  2. FUNÇÕES DE LÓGICA DO WATCHER (LÓGICA RESTAURADA)                       ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fetch-templates
  "Busca templates. Usa dados de teste se mock-mode? for true, senão chama a API real."
  [app-id token]
  (if mock-mode?
    ;; --- MODO DE TESTE ---
    (do
      (println "[MODO DE TESTE ATIVADO] Usando dados mockados para a verificação.")
      mock-templates-com-mudanca)

    ;; --- MODO REAL ---
    (let [url (str "https://api.gupshup.io/sm/api/v1/template/list/" app-id)]
      (try
        (let [response (client/get url {:headers          {:apikey token}
                                        :as               :json ; Pede para o clj-http parsear o JSON
                                        :throw-exceptions false
                                        :conn-timeout     15000 ; Aumentado para 15s
                                        :socket-timeout   15000})]
          (if (= (:status response) 200)
            (get-in (:body response) [:templates] []) ; Pega a lista de templates de forma segura
            (do
              (println (str "Erro ao buscar templates. Status: " (:status response) " | Body: " (pr-str (:body response))))
              nil)))
        (catch Exception e
          (println (str "Exceção CRÍTICA ao buscar templates: " (.getMessage e)))
          nil)))))

(defn log-change-notification
  "Loga uma notificação de mudança de template no console, no formato original."
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
  "Verifica se houve mudanças nos templates e loga-as."
  [app-id token]
  (println "[WORKER] Executando verificação de templates...")
  (if-let [templates (fetch-templates app-id token)]
    (do
      (println (str "[WORKER] " (count templates) " templates recebidos. Procurando por mudanças..."))
      (doseq [template templates]
        ;; A lógica original: verifica se a chave "oldCategory" existe.
        (when (get template "oldCategory")
          (log-change-notification template))))
    (println "[WORKER] Não foi possível obter a lista de templates para verificação.")))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;  3. LÓGICA DO SERVIDOR E PONTO DE ENTRADA (ESTRUTURA COMPATÍVEL)           ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- start-watcher-loop!
  "Inicia o loop de verificação em uma thread de fundo (background)."
  []
  (let [app-id (System/getenv "GUPSHUP_APP_ID")
        token  (System/getenv "GUPSHUP_TOKEN")]
    (if (and app-id token)
      (future
        (println "[WORKER] Watcher em background iniciado.")
        (loop []
          (check-for-changes app-id token)
          (println "[WORKER] Verificação concluída. Próximo ciclo em 10 minutos.")
          (Thread/sleep 600000) ; Pausa por 10 minutos
          (recur)))
      (println "ERRO CRÍTICO: Variáveis de ambiente GUPSHUP_APP_ID e GUPSHUP_TOKEN não definidas."))))

(defn app-handler
  "Manipulador de requisições web para o health check do Render."
  [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Serviço Notification Watcher está no ar."})

(defn -main
  "Ponto de entrada da aplicação."
  [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))]
    (println (str "[SERVER] Iniciando servidor web na porta " port "."))
    (start-watcher-loop!) ; Inicia o nosso worker
    (server/run-server #'app-handler {:port port})
    (println "[SERVER] Servidor iniciado. O watcher está rodando em background.")))

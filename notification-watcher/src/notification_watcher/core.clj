(ns notification-watcher.core
  (:require [clj-http.client :as client]
            [org.httpkit.server :as server]
            [clojure.pprint :as pprint])
  (:gen-class))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;  1. FEATURE FLAG E DADOS DE TESTE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def mock-mode?
  (= (System/getenv "MOCK_MODE") "true"))

(def mock-templates-com-mudanca
  [{"elementName" "template_normal_1", "wabaId" "111222333", "category" "MARKETING"}
   {"elementName" "template_que_mudou", "wabaId" "444555666", "category" "UTILITY", "oldCategory" "MARKETING"}
   {"elementName" "template_normal_2", "wabaId" "777888999", "category" "AUTHENTICATION"}])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;  2. FUNÇÕES DE LÓGICA DO WATCHER (COM LOG DA RESPOSTA BRUTA)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fetch-templates
  "Busca templates. Usa dados de teste ou chama a API real e LOGA A RESPOSTA BRUTA."
  [app-id token]
  (if mock-mode?
    (do
      (println "[MODO DE TESTE ATIVADO] Usando dados mockados.")
      mock-templates-com-mudanca)

    (let [url (str "https://api.gupshup.io/sm/api/v1/template/list/" app-id)]
      (println (str "[WORKER] Tentando conexão com a API. App ID: " app-id ", URL: " url))
      (try
        (println "[WORKER] PREPARANDO PARA EXECUTAR client/get...")
        (let [response (client/get url {:headers          {:apikey token}
                                        :as               :json ; Tenta parsear como JSON, mas precisamos verificar o status antes
                                        :throw-exceptions false ; Importante para podermos inspecionar respostas não-200
                                        :conn-timeout     60000 ; Aumentado para 60s
                                        :socket-timeout   60000 ; Aumentado para 60s
                                        })]
          (println "[WORKER] client/get EXECUTADO. Processando resposta...")
          (println (str "[WORKER] Resposta recebida da API. Status HTTP: " (:status response)))

          (if (= (:status response) 200)
            (do
              (println "[WORKER] Resposta da API Gupshup (status 200 OK). Corpo:")
              (pprint/pprint (:body response)) ;; Loga o corpo parseado
              (get-in (:body response) [:templates] [])) ; Extrai templates se o corpo for como esperado
            (do
              (println (str "[WORKER] Erro ao buscar templates. Status HTTP: " (:status response) ". Corpo da resposta (se houver):"))
              (pprint/pprint (:body response)) ;; Loga o corpo mesmo se não for 200, pode conter mensagens de erro da API
              nil)))
        (catch Exception e
          (println "\n!!!! [WORKER] Exceção CRÍTICA ao conectar com a API ou processar resposta !!!!")
          (println (str "Tipo da exceção: " (type e)))
          (println (str "Mensagem: " (.getMessage e)))
          (.printStackTrace e)
          (println "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n")
          nil)))))

(defn log-category-change
  [template]
  (let [id          (get template "id")
        elementName (get template "elementName")
        oldCategory (get template "oldCategory")
        newCategory (get template "category")]
    (println (str "[WORKER] Mudança de categoria detectada para o template:"))
    (println (str "  ID: " id))
    (println (str "  Nome: " elementName))
    (println (str "  Categoria Antiga: " oldCategory))
    (println (str "  Nova Categoria: " newCategory))))

(defn check-for-changes
  [app-id token]
  (println "[WORKER] Executando verificação de templates...")
  (if-let [all-templates (fetch-templates app-id token)]
    (let [templates-with-old-category (filter #(contains? % "oldCategory") all-templates)
          count-changed (count templates-with-old-category)]
      (if (pos? count-changed)
        (do
          (println (str "[WORKER] " count-changed " templates com mudança de categoria encontrados."))
          (doseq [template templates-with-old-category]
            (log-category-change template)))
        (println "[WORKER] Nenhum template com mudança de categoria encontrado."))
      (println (str "[WORKER] Total de templates recebidos da API: " (count all-templates))))
    (println "[WORKER] Não foi possível obter a lista de templates para verificação.")))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;  3. LÓGICA DO SERVIDOR E PONTO DE ENTRADA
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

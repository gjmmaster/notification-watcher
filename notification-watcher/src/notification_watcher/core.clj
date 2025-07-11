(ns notification-watcher.core
  (:require [clj-http.client :as client]
            [org.httpkit.server :as server]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.data.json :as json]) ; Added for JSON manipulation
  (:gen-class))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;  1. CONFIGURAÇÃO E ESTADO
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def gupshup-mock-mode?
  (= (System/getenv "GUPSHUP_MOCK_MODE") "true"))

(def customer-manager-mock-waba-ids-str
  (System/getenv "MOCK_CUSTOMER_MANAGER_WABA_IDS"))

(def customer-manager-url
  (System/getenv "CUSTOMER_MANAGER_URL"))

(def gupshup-token
  (System/getenv "GUPSHUP_TOKEN"))

;; Atom para armazenar os templates mockados, chaveados por WABA ID
(def mock-templates-store (atom {}))

;; Atom para armazenar a lista de templates que tiveram mudança de categoria detectada
(defonce changed-templates-atom (atom []))

(defn- generate-mock-templates-for-waba [waba-id]
  [{"id" (str "tpl_normal_" waba-id "_1") "elementName" (str "Template Normal " waba-id " Alpha") "wabaId" waba-id "category" "MARKETING" "status" "APPROVED" "language" "pt_BR"}
   {"id" (str "tpl_changed_" waba-id "_2") "elementName" (str "Template Alterado " waba-id " Bravo") "wabaId" waba-id "category" "UTILITY" "oldCategory" "MARKETING" "status" "APPROVED" "language" "en_US"}
   {"id" (str "tpl_failed_" waba-id "_3") "elementName" (str "Template Falhado " waba-id " Charlie") "wabaId" waba-id "category" "AUTHENTICATION" "status" "FAILED" "language" "es_ES"}
   {"id" (str "tpl_another_changed_" waba-id "_4") "elementName" (str "Outro Alterado " waba-id " Delta") "wabaId" waba-id "category" "AUTHENTICATION" "oldCategory" "UTILITY" "status" "APPROVED" "language" "pt_BR"}])

(defn- initialize-mock-gupshup-store! []
  (when gupshup-mock-mode?
    (println "[MOCK_SETUP] GUPSHUP_MOCK_MODE está ativo.")
    (let [mock-waba-ids (if customer-manager-mock-waba-ids-str
                          (map str/trim (str/split customer-manager-mock-waba-ids-str #","))
                          ["mock_waba_id_default1" "mock_waba_id_default2"])] ; Fallback se MOCK_CUSTOMER_MANAGER_WABA_IDS não estiver setado mas GUPSHUP_MOCK_MODE sim
      (println (str "[MOCK_SETUP] Populando mock store para WABA IDs: " mock-waba-ids))
      (doseq [waba-id mock-waba-ids]
        (swap! mock-templates-store assoc waba-id (generate-mock-templates-for-waba waba-id)))
      (println (str "[MOCK_SETUP] Mock store populado: " @mock-templates-store)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;  2. FUNÇÕES DE LÓGICA DO WATCHER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fetch-waba-ids
  "Busca WABA IDs do customer-manager-service ou usa mock."
  []
  (if customer-manager-mock-waba-ids-str
    (do
      (println (str "[WORKER] Usando MOCK_CUSTOMER_MANAGER_WABA_IDS: " customer-manager-mock-waba-ids-str))
      (map str/trim (str/split customer-manager-mock-waba-ids-str #",")))
    (if customer-manager-url
      (try
        (let [full-url (str customer-manager-url "/internal/customers/active-waba-ids")]
          (println (str "[WORKER] Buscando WABA IDs de: " full-url))
          (let [response (client/get full-url {:as :json :throw-exceptions false :conn-timeout 5000 :socket-timeout 5000})]
            (if (= (:status response) 200)
              (let [ids (:body response)]
                (if (and (vector? ids) (every? string? ids))
                  (do
                    (println (str "[WORKER] WABA IDs recebidos: " ids))
                    ids)
                  (do
                    (println (str "[WORKER] Resposta inesperada do customer-manager-service (formato inválido): " (:body response) ". Esperado um vetor de strings."))
                    [])))
              (do
                (println (str "[WORKER] Erro ao buscar WABA IDs do customer-manager-service. Status: " (:status response) ". Corpo: " (:body response)))
                []))))
        (catch Exception e
          (println (str "[WORKER] Exceção ao buscar WABA IDs do customer-manager-service: " (.getMessage e)))
          (.printStackTrace e)
          []))
      (do
        (println "[WORKER] ERRO: Nem MOCK_CUSTOMER_MANAGER_WABA_IDS nem CUSTOMER_MANAGER_URL estão definidos. Não é possível buscar WABA IDs.")
        []))))

(defn fetch-templates-for-waba
  "Busca templates para um WABA ID específico. Usa dados de teste ou chama a API real."
  [waba-id token]
  (if gupshup-mock-mode?
    (do
      (println (str "[GUPSHUP_MOCK_MODE] Usando dados mockados para WABA ID: " waba-id))
      (let [mocked-data (get @mock-templates-store waba-id [])]
        (println (str "[GUPSHUP_MOCK_MODE] Dados para " waba-id ": " (count mocked-data) " templates."))
        mocked-data))

    (let [url (str "https://api.gupshup.io/sm/api/v1/template/list/" waba-id)]
      (println (str "[WORKER] Tentando conexão com a API Gupshup. WABA ID: " waba-id ", URL: " url))
      (try
        (let [response (client/get url {:headers          {:apikey token}
                                        :as               :json
                                        :throw-exceptions false
                                        :conn-timeout     60000
                                        :socket-timeout   60000})]
          (println (str "[WORKER] Resposta recebida da API Gupshup para WABA ID " waba-id ". Status HTTP: " (:status response)))

          (cond
            (:error response)
            (do
              (println (str "[WORKER] Erro crítico de HTTP ao buscar templates para WABA ID " waba-id ": " (:error response)))
              (when-let [cause (:cause (:error response))]
                (println (str "Causa: " cause)))
              nil) ; Retorna nil em caso de erro crítico de HTTP

            (not= (:status response) 200)
            (do
              (println (str "[WORKER] Erro ao buscar templates da Gupshup para WABA ID " waba-id ". Status HTTP: " (:status response) ". Corpo:"))
              (pprint/pprint (:body response))
              nil) ; Retorna nil se o status não for 200

            (not (map? (:body response)))
            (do
              (println (str "[WORKER] Resposta da API Gupshup (status 200 OK) para WABA ID " waba-id " mas corpo não é um mapa JSON. Corpo:"))
              (pprint/pprint (:body response))
              (println (str "\n!!!! [WORKER] ALERTA: Corpo da API Gupshup para WABA ID " waba-id " não é um mapa JSON válido. Retornando nil. !!!!"))
              nil) ; Retorna nil se o corpo não for um mapa

            :else
            (let [templates (get-in (:body response) [:templates])]
              (println (str "[WORKER] Templates recebidos da API Gupshup para WABA ID " waba-id ": " (count templates) " templates."))
              (or templates [])))) ; Garante [] se :templates for nil ou ausente na resposta bem-sucedida

        (catch Exception e
          (println (str "\n!!!! [WORKER] Exceção CRÍTICA ao conectar com a API Gupshup para WABA ID " waba-id " ou processar resposta !!!!"))
          (println (str "Tipo da exceção: " (type e)))
          (println (str "Mensagem: " (.getMessage e)))
          (.printStackTrace e)
          nil))))) ; Retorna nil em caso de exceção durante a chamada HTTP

(defn log-category-change
  "Loga a mudança de categoria de um template."
  [template waba-id]
  (let [template-id (get template "id") ; Gupshup API usa strings para chaves
        elementName (get template "elementName")
        oldCategory (get template "oldCategory")
        newCategory (get template "category")]
    (println (str "[WORKER] Mudança de categoria detectada:"))
    (println (str "  WABA ID: " waba-id))
    (println (str "  ID do Template: " template-id))
    (println (str "  Nome: " elementName))
    (println (str "  Categoria Antiga: " oldCategory))
    (println (str "  Nova Categoria: " newCategory))))

(defn process-templates-for-waba
  "Processa templates para um WABA ID, identifica mudanças e retorna os alterados,
   associando o waba-id a cada template alterado."
  [waba-id token]
  (println (str "[WORKER] Iniciando processamento de templates para WABA ID: " waba-id))
  (if-let [all-templates (fetch-templates-for-waba waba-id token)] ; all-templates pode ser nil se fetch falhar
    (if (seq all-templates) ; Procede somente se houver templates
      (let [total-received (count all-templates)
            map-templates (filter map? all-templates) ; Garante que são mapas
            ;; Filtra para incluir apenas templates que NÃO estão com status "FAILED"
            active-templates (filter #(not= (get % "status") "FAILED") map-templates)
            total-active (count active-templates)]

        ;; DEBUG: Log detalhado de cada template ativo
        (println (str "[WORKER_DEBUG] Verificando " total-active " templates ativos para WABA ID: " waba-id))
        (doseq [template active-templates]
          (let [tpl-id (get template "id" "N/A")
                tpl-name (get template "elementName" "N/A")
                tpl-cat (get template "category" "N/A")
                has-old-cat (contains? template "oldCategory")
                tpl-old-cat (get template "oldCategory" "N/A")]
            (println (str "[WORKER_DEBUG] Template ID: " tpl-id
                          ", Nome: \"" tpl-name
                          "\", Categoria Atual: " tpl-cat
                          ", Possui 'oldCategory'?: " has-old-cat
                          ", Valor 'oldCategory': " tpl-old-cat))))

        ;; Filtra templates ativos que possuem a chave "oldCategory" (indicando mudança)
        (let [changed-category-templates (filter #(contains? % "oldCategory") active-templates)
              count-changed (count changed-category-templates)]

          (println (str "[WORKER] Detalhes para WABA ID " waba-id ":"))
          (println (str "  Total de templates recebidos: " total-received "."))
        (when (not= total-received (count map-templates))
          (println (str "  AVISO: " (- total-received (count map-templates)) " itens não-mapa foram ignorados.")))
        (println (str "  Total de templates ativos (não FAILED e são mapas): " total-active "."))

        (if (pos? count-changed)
          (do
            (println (str "  Dentre os ativos, " count-changed " templates com mudança de categoria encontrados."))
            (doseq [template changed-category-templates]
              (log-category-change template waba-id))
            ;; Adiciona/atualiza o wabaId em cada template alterado para garantir consistência
            (map #(assoc % "wabaId" waba-id) changed-category-templates))
          (do
            (println (str "  Nenhum template ativo com mudança de categoria encontrado para o WABA ID " waba-id "."))
            []))) ; Retorna lista vazia se não houver mudanças
      (do
        (println (str "[WORKER] Nenhum template recebido ou lista vazia para WABA ID " waba-id "."))
        [])) ; Retorna lista vazia se não houve templates
    (do
      (println (str "[WORKER] Falha ao obter templates para WABA ID " waba-id ". Não foi possível processar."))
      []))) ; Retorna lista vazia em caso de falha total no fetch

(defn check-all-waba-ids-for-changes!
  "Busca todos os WABA IDs e verifica mudanças de templates para cada um,
   atualizando o atom `changed-templates-atom`."
  []
  (println "[WORKER] Iniciando ciclo de verificação de todos os WABA IDs...")
  (if (str/blank? gupshup-token)
    (println "[WORKER] ERRO CRÍTICO: GUPSHUP_TOKEN não está definido. Não é possível buscar templates.")
    (let [waba-ids (fetch-waba-ids)]
      (if (empty? waba-ids)
        (do
          (println "[WORKER] Nenhum WABA ID para processar. Limpando lista de templates alterados.")
          (reset! changed-templates-atom []))
        (let [all-changed-templates (->> waba-ids
                                         (mapcat #(process-templates-for-waba % gupshup-token))
                                         (filter identity) ; Remove nils que podem ter vindo de process-templates-for-waba
                                         vec)] ; Converte para vetor
          (println (str "[WORKER] Total de templates com mudança de categoria em todos os WABA IDs: " (count all-changed-templates)))
          (reset! changed-templates-atom all-changed-templates)
          (if (seq @changed-templates-atom)
            (println (str "[WORKER] Lista de templates alterados atualizada. " (count @changed-templates-atom) " templates armazenados."))
            (println "[WORKER] Lista de templates alterados está vazia após a verificação.")))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;  3. LÓGICA DO SERVIDOR HTTP E PONTO DE ENTRADA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- start-watcher-loop!
  "Inicia o loop em background que periodicamente verifica por mudanças."
  []
  (initialize-mock-gupshup-store!) ; Garante que o mock store seja inicializado se o modo mock estiver ativo.
  (if (str/blank? gupshup-token)
    (println "[WORKER_SETUP] ERRO CRÍTICO: GUPSHUP_TOKEN não está definido. O watcher não pode iniciar.")
    (future
      (println "[WORKER] Watcher em background iniciado. Primeiro ciclo em 30 segundos...")
      (Thread/sleep 30000) ; Atraso inicial
      (loop []
        (try
          (check-all-waba-ids-for-changes!) ; Chama a função que atualiza o atom
          (println "[WORKER] Verificação de todos os WABA IDs concluída. Próximo ciclo em 10 minutos.")
          (catch Throwable t ; Captura qualquer Throwable para evitar que o loop morra
            (println "\n!!!! [WORKER] Exceção INESPERADA no loop principal do watcher !!!!")
            (println (str "  Tipo da exceção: " (type t)))
            (println (str "  Mensagem: " (.getMessage t)))
            (.printStackTrace t)
            (println "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n")
            (println "[WORKER] Erro no ciclo. Tentando novamente em 10 minutos.")))
        (Thread/sleep 600000) ; Intervalo de 10 minutos (600,000 ms)
        (recur)))))

(defn app-handler
  "Manipulador de requisições HTTP."
  [request]
  (case (:uri request)
    "/"
    {:status  200
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body    "Serviço Notification Watcher está no ar."}

    "/changed-templates"
    {:status  200
     :headers {"Content-Type" "application/json; charset=utf-8"}
     :body    (json/write-str @changed-templates-atom)} ; Serve o conteúdo do atom

    ;; else (404 Not Found)
    {:status  404
     :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body    "Recurso não encontrado."}))

(defn -main
  "Ponto de entrada principal da aplicação."
  [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))]
    (println (str "[SERVER] Notification Watcher iniciando..."))
    (println (str "[SERVER] Configurações:"))
    (println (str "  GUPSHUP_MOCK_MODE: " gupshup-mock-mode?))
    (println (str "  MOCK_CUSTOMER_MANAGER_WABA_IDS: " (if customer-manager-mock-waba-ids-str customer-manager-mock-waba-ids-str "Não definido")))
    (println (str "  CUSTOMER_MANAGER_URL: " (if customer-manager-url customer-manager-url "Não definido")))
    (println (str "  GUPSHUP_TOKEN: " (if (str/blank? gupshup-token) "NÃO DEFINIDO (CRÍTICO!)" "Definido (oculto)")))
    (println (str "  PORT: " port))

    (start-watcher-loop!) ; Inicia o processo em background

    (server/run-server #'app-handler {:port port})
    (println (str "[SERVER] Servidor HTTP iniciado na porta " port "."))
    (println "[SERVER] Notification Watcher está rodando.")))

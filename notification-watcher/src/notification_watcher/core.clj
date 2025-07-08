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
  [{"elementName" "template_que_mudou_1", "wabaId" "444555666", "category" "UTILITY", "oldCategory" "MARKETING"}
   {"elementName" "template_que_mudou_2", "wabaId" "777888999", "category" "MARKETING", "oldCategory" "UTILITY"}])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                    FUNÇÕES DE LÓGICA DO WATCHER                  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fetch-templates
  "Busca templates. Usa dados de teste se mock-mode? for true, senão chama a API real."
  [app-id token]
  (if mock-mode?
    (do
      (println "[MODO DE TESTE ATIVADO] Usando dados mockados para a verificação.")
      mock-templates-com-mudanca)
      
    (let [url (str "https://api.gupshup.io/sm/api/v1/template/list/" app-id)]
      (try
        (let [response (client/get url {:headers {"apikey" token}
                                        :as :json
                                        :throw-exceptions false})]
          (if (= (:status response) 200)
            ;; A API pode retornar um JSON com a chave "templates" ou ser um array direto.
            ;; Esta linha trata ambos os casos de forma segura.
            (let [body (:body response)]
              (if (vector? body) body (get body "templates")))
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
    (println "\n--- MUDANÇA DE CATEGORIA ENCONTRADA ---")
    (println (str "Template: '" elementName "' (WABA ID: " wabaId ")"))
    (println (str "Categoria Anterior: " oldCategory))
    (println (str "Nova Categoria: " category))
    (println "---------------------------------------\n")))

(defn check-for-changes
  "Verifica e reporta todas as mudanças encontradas nos templates."
  [app-id token]
  (println "Iniciando varredura por mudanças de categoria nos templates...")
  (if-let [templates (fetch-templates app-id token)]
    (let [mudancas (filter #(contains? % "oldCategory") templates)]
      (if (empty? mudancas)
        (println "Nenhuma mudança de categoria foi encontrada.")
        (do
          (println (str "Encontradas " (count mudancas) " mudanças:"))
          (doseq [template mudancas]
            (log-change-notification template)))))
    (println "Falha ao obter templates. A verificação não pôde ser concluída.")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                    LÓGICA DO SERVIDOR E PONTO DE ENTRADA                 ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; A função do servidor web continua a mesma para manter o serviço vivo no Render
(defroutes app-routes
  "Define as rotas da nossa aplicação web."
  (GET "/" [] "Serviço Notification Watcher está no ar e pronto para verificações.")
  (route/not-found "Página não encontrada."))

(defn -main
  "Ponto de entrada da aplicação. AGORA EXECUTA A VERIFICAÇÃO UMA VEZ E FICA ATIVO."
  [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))
        app-id (System/getenv "GUPSHUP_APP_ID")
        token (System/getenv "GUPSHUP_TOKEN")]
    
    ;; Inicia o servidor web para responder aos pings do UptimeRobot
    (server/run-server #'app-routes {:port port})
    (println (str "Servidor web iniciado na porta " port ". Serviço pronto."))

    ;; Executa a verificação uma única vez no início
    (if (and app-id token)
      (check-for-changes app-id token)
      (println "ERRO: Variáveis de ambiente GUPSHUP_APP_ID e GUPSHUP_TOKEN não definidas. A verificação inicial não será executada."))))

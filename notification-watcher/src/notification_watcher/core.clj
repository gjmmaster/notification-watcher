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
  [{"elementName" "template_que_mudou_1", "wabaId" "444555666", "category" "UTILITY", "oldCategory" "MARKETING"}])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;                    FUNÇÕES DE LÓGICA DO WATCHER                  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fetch-templates
  "Busca templates com LOGS DE DEPURAÇÃO ADICIONAIS."
  [app-id token]
  (if mock-mode?
    (do
      (println "[MODO DE TESTE ATIVADO] Usando dados mockados para a verificação.")
      mock-templates-com-mudanca)
      
    (let [url (str "https://api.gupshup.io/sm/api/v1/template/list/" app-id)]
      ;; --- INÍCIO DOS LOGS DE DEPURAÇÃO ---
      (println (str "DEBUG: Tentando chamar a URL: " url))
      (println (str "DEBUG: Usando token que começa com: '" (subs token 0 (min 4 (count token))) "...'"))
      ;; --- FIM DOS LOGS DE DEPURAÇÃO ---
      (try
        (let [response (client/get url {:headers {"apikey" token}
                                        :as :json
                                        :throw-exceptions false})]
          ;; --- INÍCIO DOS LOGS DE DEPURAÇÃO ---
          (println (str "DEBUG: Resposta completa recebida da API: " (pr-str response)))
          ;; --- FIM DOS LOGS DE DEPURAÇÃO ---
          (if (= (:status response) 200)
            (let [body (:body response)]
              (if (vector? body) body (get body "templates")))
            (do
              (println (str "ERRO DETALHADO: A API retornou um status não-200. Status: " (:status response)))
              nil)))
        (catch Exception e
          (println (str "EXCEÇÃO DETALHADA: Ocorreu uma exceção na chamada HTTP: " e))
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

(defroutes app-routes
  "Define as rotas da nossa aplicação web."
  (GET "/" [] "Serviço Notification Watcher está no ar e pronto para verificações.")
  (route/not-found "Página não encontrada."))

(defn -main
  "Ponto de entrada da aplicação. Executa a verificação uma vez e fica ativo."
  [& args]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8080"))
        app-id (System/getenv "GUPSHUP_APP_ID")
        token (System/getenv "GUPSHUP_TOKEN")]
    (server/run-server #'app-routes {:port port})
    (println (str "Servidor web iniciado na porta " port ". Serviço pronto."))
    (if (and app-id token)
      (check-for-changes app-id token)
      (println "ERRO: Variáveis de ambiente GUPSHUP_APP_ID e GUPSHUP_TOKEN não definidas."))))

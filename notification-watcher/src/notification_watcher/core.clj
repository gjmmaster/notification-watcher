(ns notification-watcher.core
  (:require [clj-http.client :as client]
            [cheshire.core :as json])
  (:gen-class))

(defn fetch-templates
  "Busca templates da API Gupshup."
  [app-id token]
  (let [url (str "https://partner.gupshup.io/partner/app/" app-id "/templates")]
    (try
      (let [response (client/get url {:headers {:Authorization token}
                                      :as :json
                                      :throw-exceptions false})]
        (if (= (:status response) 200)
          (-> response :body :templates) ; Ajustado para pegar a lista de templates do corpo da resposta
          (do
            (println (str "Erro ao buscar templates. Status: " (:status response) " Body: " (:body response)))
            nil)))
      (catch Exception e
        (println (str "Exceção ao buscar templates: " (.getMessage e)))
        nil))))

(defn log-change-notification
  "Loga uma notificação de mudança de template."
  [template]
  (let [{:keys [elementName wabaId oldCategory category]} template]
    (println (str "Mudança detectada no template '" elementName "' (WABA ID: " wabaId "):"))
    (println (str "  Categoria anterior: " oldCategory))
    (println (str "  Nova categoria: " category))
    (println "Objeto JSON do evento da mudança:")
    (println (json/generate-string template {:pretty true}))
    (println "---")))

(defn check-for-changes
  "Verifica se houve mudanças nos templates."
  [app-id token]
  (if-let [templates (fetch-templates app-id token)]
    (doseq [template templates]
      (when (:oldCategory template)
        (log-change-notification template)))
    (println "Não foi possível obter os templates para verificação.")))

(defn -main
  "Ponto de entrada da aplicação. Inicia o loop de verificação."
  [& args]
  (let [app-id (System/getenv "GUPSHUP_APP_ID")
        token (System/getenv "GUPSHUP_TOKEN")]
    (if (and app-id token)
      (loop []
        (println "Executando verificação...")
        (check-for-changes app-id token)
        (println "Verificação concluída. Aguardando 10 minutos...")
        (Thread/sleep 600000) ; 10 minutos = 10 * 60 * 1000 milissegundos
        (recur))
      (println "Erro: As variáveis de ambiente GUPSHUP_APP_ID e GUPSHUP_TOKEN devem ser definidas."))))

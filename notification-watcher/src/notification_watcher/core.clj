(ns notification-watcher.core
  (:require [notification-watcher.gupshup-service :as gupshup]
            [org.httpkit.server :as server])
  (:gen-class))

(defn start-gupshup-worker
  "Esta função contém a lógica que roda em loop em segundo plano,
  verificando e agindo sobre as mudanças de templates."
  []
  (println "===> [WORKER] Iniciando worker em modo contínuo <===")
  (loop []
    (let [templates (gupshup/get-approved-templates)]
      (if (seq templates)
        (do
          (println "\n=== [WORKER] Iniciando processamento da lógica de negócio ===")
          (doseq [template templates]
            ;; --- LÓGICA DE NEGÓCIO ROBUSTA ---
            ;; Usamos (or (:chave template) (get template "chave")) para garantir
            ;; que pegamos o valor independentemente de a chave ser :keyword ou "string".
            (let [template-name (or (:elementName template) (get template "elementName"))
                  new-category  (or (:category template) (get template "category"))
                  old-category  (or (:oldCategory template) (get template "oldCategory"))]

              (if (and old-category (not-empty old-category))
                ;; SIM, HOUVE MUDANÇA
                (let [business-account-name "JM Master"
                      notification-message (format
                        "ALERTA: A categoria do modelo '%s' na conta %s foi atualizada de %s para %s."
                        template-name
                        business-account-name
                        old-category
                        new-category)]
                  (println notification-message))
                ;; NÃO, SEM MUDANÇA
                (println (str "INFO: Template '" template-name "' verificado, sem alteração de categoria."))))))
        
        (println "[WORKER] Nenhum template para processar no momento.")))

    (println "\n[WORKER] Verificação concluída. Aguardando 10 minutos para o próximo ciclo...")
    (Thread/sleep 600000)
    (recur)))

(defn app-handler
  "Manipulador de requisições web. Responde ao 'health check' do Render."
  [request]
  (println "===> [SERVER] Recebida requisição de health check do Render.")
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Notification Watcher service is running."})

(defn -main
  "Função principal da aplicação."
  [& args]
  (future (start-gupshup-worker))
  (let [port-str (System/getenv "PORT")
        port (if port-str (Integer/parseInt port-str) 8080)]
    (println (str "===> [SERVER] Iniciando servidor web na porta " port))
    (server/run-server #'app-handler {:port port})
    (println "===> [SERVER] Servidor web iniciado com sucesso.")))

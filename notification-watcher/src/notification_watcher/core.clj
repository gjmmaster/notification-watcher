(ns notification-watcher.core
  (:require [notification-watcher.gupshup-service :as gupshup]
            [org.httpkit.server :as server]
            [clojure.pprint :as pprint]) ; Usado para imprimir erros de forma legível
  (:gen-class))

(defn start-gupshup-worker
  "Função do worker com tratamento de erros robusto."
  []
  (println "===> [WORKER] Iniciando worker em modo contínuo <===")
  (loop []
    (try
      ;; --- INÍCIO DO BLOCO SEGURO ---
      (println "===> [WORKER] Ciclo iniciado. Chamando o serviço da Gupshup...")
      (let [templates (gupshup/get-approved-templates)]
        (println (str "===> [WORKER] Serviço retornou " (count templates) " templates. Iniciando processamento..."))

        (if (seq templates)
          (do
            (println "\n=== [WORKER] Iniciando processamento da lógica de negócio ===")
            (doseq [template templates]
              (let [template-name (or (:elementName template) (get template "elementName"))
                    new-category  (or (:category template) (get template "category"))
                    old-category  (or (:oldCategory template) (get template "oldCategory"))]

                (if (and old-category (not-empty old-category))
                  (let [business-account-name "JM Master"
                        notification-message (format
                          "ALERTA: A categoria do modelo '%s' na conta %s foi atualizada de %s para %s."
                          template-name
                          business-account-name
                          old-category
                          new-category)]
                    (println notification-message))
                  (println (str "INFO: Template '" template-name "' verificado, sem alteração de categoria."))))))
          (println "[WORKER] Nenhum template para processar no momento.")))
      ;; --- FIM DO BLOCO SEGURO ---

      ;; --- CAPTURA E EXIBIÇÃO DE QUALQUER ERRO ---
      (catch Exception e
        (println "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        (println "!!!!!!   ERRO CRÍTICO NO WORKER. CICLO INTERROMPIDO   !!!!!!")
        (println "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        (println "\nCAUSA DO ERRO:" (.getMessage e))
        (println "\nRASTRO COMPLETO (STACK TRACE):")
        (pprint/pprint (.getStackTrace e)) ; Imprime o rastro completo do erro
        (println "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")))

    ;; --- Pausa entre os ciclos ---
    (println "\n[WORKER] Verificação concluída. Aguardando 10 minutos para o próximo ciclo...")
    (Thread/sleep 600000)
    (recur)))

(defn app-handler
  "Manipulador de requisições web."
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

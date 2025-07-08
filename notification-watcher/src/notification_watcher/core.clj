(ns notification-watcher.core
  (:require [notification-watcher.gupshup-service :as gupshup]
            [org.httpkit.server :as server])
  (:gen-class))

(defn start-gupshup-worker
  "Esta função contém a lógica que roda em loop em segundo plano,
  verificando e agindo sobre as mudanças de templates."
  []
  (println "===> [WORKER] Iniciando worker em modo contínuo <===")
  (loop [] ; Inicia um loop infinito
    (let [templates (gupshup/get-approved-templates)]
      ;; Estrutura do IF/ELSE corrigida aqui
      (if (seq templates)
        ;; ---- THEN (se houver templates) ----
        (do
          (println "\n=== [WORKER] Iniciando processamento da lógica de negócio ===")
          (doseq [template templates]
            (let [template-name (:elementName template)
                  new-category  (:category template)
                  old-category  (:oldCategory template)]
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
        ;; ---- ELSE (se não houver templates) ----
        (println "[WORKER] Nenhum template para processar no momento.")))

    ;; --- Pausa entre os ciclos ---
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

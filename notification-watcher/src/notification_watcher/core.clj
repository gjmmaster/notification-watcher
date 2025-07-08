(ns notification-watcher.core
  (:require [notification-watcher.gupshup-service :as gupshup]
            [org.httpkit.server :as server]) ; Importa o servidor http-kit
  (:gen-class))

(defn start-gupshup-worker
  "Esta função contém a lógica que roda em loop em segundo plano."
  []
  (println "===> [WORKER] Iniciando worker em modo contínuo <===")
  (loop [] ; O seu loop original está aqui
    (let [templates-aprovados (gupshup/get-approved-templates)]
      (if (seq templates-aprovados)
        (do
          (println "\n=== [WORKER] Iniciando processamento da lógica de negócio ===")
          (doseq [template templates-aprovados]
            (println (str "[WORKER] Executando ação para o template: " (:elementName template) " (ID: " (:id template) ")"))

            ;; =======================================================
            ;;   COLE A SUA LÓGICA DE NEGÓCIO ESPECÍFICA AQUI
            ;; =======================================================

            ))
        (println "[WORKER] Nenhum template para processar no momento.")))

    ;; --- Pausa para não sobrecarregar a API ---
    (println "\n[WORKER] Verificação concluída. Aguardando 10 minutos para o próximo ciclo...")
    (Thread/sleep 600000) ; Pausa de 10 minutos

    (recur))) ; Reinicia o loop

(defn app-handler
  "Este é o manipulador de requisições web. Ele só precisa dizer que está tudo bem."
  [request]
  (println "===> [SERVER] Recebida requisição de health check do Render.")
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Notification Watcher is running."})

(defn -main
  "Função principal: inicia o worker em background E o servidor web."
  [& args]
  ;; 1. Inicia sua lógica de verificação em uma thread separada (future)
  (future (start-gupshup-worker))

  ;; 2. Inicia o servidor web para manter o serviço "vivo" no Render
  (let [port-str (System/getenv "PORT")
        port (if port-str (Integer/parseInt port-str) 8080)] ; Render fornece a porta via variável de ambiente
    (println (str "===> [SERVER] Iniciando servidor web na porta " port))
    (server/run-server #'app-handler {:port port})
    (println "===> [SERVER] Servidor web iniciado.")))

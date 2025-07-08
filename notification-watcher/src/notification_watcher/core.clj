(ns notification-watcher.core
  (:require [notification-watcher.gupshup-service :as gupshup]
            [org.httpkit.server :as server]) ; Importa o servidor http-kit
  (:gen-class))

(defn start-gupshup-worker
  "Esta função contém a lógica que roda em loop em segundo plano,
  verificando e agindo sobre as mudanças de templates."
  []
  (println "===> [WORKER] Iniciando worker em modo contínuo <===")
  (loop [] ; Inicia um loop infinito
    (let [templates (gupshup/get-approved-templates)] ; Busca todos os templates aprovados
      (if (seq templates)
        (do
          (println "\n=== [WORKER] Iniciando processamento da lógica de negócio ===")
          (doseq [template templates]
            ;; --- LÓGICA DE NEGÓCIO ESPECÍFICA ---
            (let [template-name (:elementName template)
                  new-category  (:category template)
                  old-category  (:oldCategory template)]

              ;; A CONDIÇÃO PRINCIPAL:
              ;; Verifica se o campo 'oldCategory' existe e não está vazio.
              (if (and old-category (not-empty old-category))
                
                ;; SIM, HOUVE MUDANÇA DE CATEGORIA!
                (let [;; No futuro, este nome pode vir de uma configuração ou do próprio template
                      business-account-name "JM Master" 
                      
                      ;; Monta a mensagem de notificação final
                      notification-message (format
                        "ALERTA: A categoria do modelo '%s' na conta %s foi atualizada de %s para %s."
                        template-name
                        business-account-name
                        old-category
                        new-category)]

                  ;; 1. Imprime o alerta no log para sabermos que funcionou
                  (println notification-message)

                  ;; 2. NO FUTURO: aqui você enviará a notificação para o segundo serviço
                  ;; Ex: (segundo-servico/enviar-notificacao {:mensagem notification-message})
                  )

                ;; NÃO, NENHUMA MUDANÇA DETECTADA PARA ESTE TEMPLATE.
                ;; Imprime uma mensagem informativa para sabermos que ele foi verificado.
                (println (str "INFO: Template '" template-name "' verificado, sem alteração de categoria.")))))
        
        ;; Caso a API não retorne nenhum template
        (println "[WORKER] Nenhum template para processar no momento.")))

    ;; --- Pausa entre os ciclos ---
    (println "\n[WORKER] Verificação concluída. Aguardando 10 minutos para o próximo ciclo...")
    (Thread/sleep 600000) ; Pausa de 10 minutos

    (recur))) ; 'recur' reinicia o loop de forma eficiente

(defn app-handler
  "Manipulador de requisições web. Responde ao 'health check' do Render."
  [request]
  (println "===> [SERVER] Recebida requisição de health check do Render.")
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Notification Watcher service is running."})

(defn -main
  "Função principal da aplicação.
  1. Inicia o worker da Gupshup em uma thread de segundo plano.
  2. Inicia o servidor web para manter o serviço ativo no Render."
  [& args]
  ;; 1. Inicia sua lógica de verificação em uma thread separada para não bloquear o servidor
  (future (start-gupshup-worker))

  ;; 2. Inicia o servidor web, que é o que mantém o serviço "vivo" no Render
  (let [port-str (System/getenv "PORT")
        port (if port-str (Integer/parseInt port-str) 8080)] ; O Render fornece a porta via variável de ambiente
    (println (str "===> [SERVER] Iniciando servidor web na porta " port))
    (server/run-server #'app-handler {:port port})
    (println "===> [SERVER] Servidor web iniciado com sucesso.")))

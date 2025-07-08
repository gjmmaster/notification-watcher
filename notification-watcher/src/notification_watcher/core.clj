;; no arquivo: src/notification_watcher/core.clj

(ns notification-watcher.core
  (:require [notification-watcher.gupshup-service :as gupshup]) ; Importa nosso serviço com o alias 'gupshup'
  (:gen-class))

(defn -main
  "Função principal da aplicação, agora focada na regra de negócio."
  [& args]
  ;; 1. Chama a função do nosso serviço para obter dados já limpos e validados
  (let [templates-aprovados (gupshup/get-approved-templates)]

    ;; 2. Lógica de negócio que trabalha com dados seguros e previsíveis
    (if (seq templates-aprovados)
      (do
        (println "\n=== Iniciando processamento da lógica de negócio ===")
        (doseq [template templates-aprovados]
          ;; 'template' é um mapa limpo, garantido pelo nosso serviço.
          ;; Você pode acessar as chaves com segurança.
          (println (str "Executando ação para o template: " (:elementName template) " (ID: " (:id template) ")"))

          ;;
          ;; =======================================================
          ;;    COLE A SUA LÓGICA DE NEGÓCIO ESPECÍFICA AQUI
          ;; =======================================================
          ;;
          
          ))
      (println "Nenhum template para processar.")))
  
  ;; Garante que a aplicação finalize corretamente.
  (shutdown-agents))

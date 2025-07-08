(ns notification-watcher.core
  (:require [notification-watcher.gupshup-service :as gupshup]) ; Importa nosso serviço com o alias 'gupshup'
  (:gen-class))

(defn -main
  "Função principal que agora roda em um loop contínuo para verificação."
  [& args]
  (println "===> INICIANDO WORKER EM MODO CONTÍNUO <===")
  (loop [] ; Inicia um loop infinito
    (let [templates-aprovados (gupshup/get-approved-templates)]
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
        (println "Nenhum template para processar no momento.")))

    ;; --- Pausa para não sobrecarregar a API ---
    (println "\nVerificação concluída. Aguardando 10 minutos para o próximo ciclo...")
    ;; 10 minutos * 60 segundos * 1000 milissegundos = 600000
    (Thread/sleep 600000)

    (recur))) ; 'recur' reinicia o loop sem consumir mais memória

;; Garante que a aplicação finalize corretamente caso o loop seja interrompido no futuro.
(shutdown-agents)

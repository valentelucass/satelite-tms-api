'use strict';
const path = require('node:path');
const namespace = 'satelite-tms';
const programas = ['api-integracoes-19090', 'clientes'];
const anteriores = {
  'api-integracoes-19090': ['Satelite-API-19090'],
  clientes: ['ETL-SELIA-Worker', 'RECONCILIACAO-CLIENTES', 'WORK-SFTP-CLIENTES']
};
const raiz = path.resolve(__dirname, '../..');

async function executar(pm2, acao, nomes) {
  const chamar = (metodo, ...args) => new Promise((resolve, reject) =>
    pm2[metodo](...args, (erro, resultado) => erro ? reject(erro) : resolve(resultado)));
  await chamar('connect');
  try {
    for (const nome of nomes) {
      const lista = await chamar('list');
      const encontrados = lista.filter(p => p.name === nome);
      if (encontrados.length > 1 || encontrados.some(p => p.pm2_env.namespace !== namespace ||
          path.resolve(p.pm2_env.pm_cwd).toLowerCase() !== raiz.toLowerCase())) {
        throw Error('Cadastro com o mesmo nome pertence a outro processo: ' + nome);
      }
      const processo = encontrados[0];
      const status = processo?.pm2_env.status ?? 'ausente';
      if (acao === 'status') {
        console.log(namespace + '/' + nome, status, processo?.pid ? 'PID ' + processo.pid : '');
      } else if (acao === 'parar') {
        if (processo && status !== 'stopped') await chamar('stop', processo.pm_id);
      } else if (status !== 'online' && status !== 'launching') {
        if (lista.some(p => anteriores[nome].includes(p.name) && p.pm2_env.status !== 'stopped')) {
          throw Error('Há um cadastro anterior ativo para ' + nome + '. Conclua a troca de processos antes de iniciar.');
        }
        await chamar('start', path.join(__dirname, 'ecosystem.config.js'), {only: nome});
      }
    }
  } finally {
    pm2.disconnect();
  }
}

if (require.main === module) {
  const [acao, nome = 'todos', ...extra] = process.argv.slice(2);
  if (!['status', 'iniciar', 'parar'].includes(acao) || ![...programas, 'todos'].includes(nome) || extra.length) {
    console.error('Uso: node operar.cjs status|iniciar|parar [api-integracoes-19090|clientes|todos]');
    process.exitCode = 2;
  } else {
    const pm2 = require(path.join(process.env.APPDATA, 'npm/node_modules/pm2'));
    executar(pm2, acao, nome === 'todos' ? programas : [nome]).catch(erro => {
      console.error(erro.message);
      process.exitCode = 1;
    });
  }
}

module.exports = {executar};

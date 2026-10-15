/**
 * pc-gateway/index.js —— DSH Cordis 插件 `@local/dsh-remote-gateway`
 *
 * 自建 node:http 服务监听 0.0.0.0:<port>，完全独立于 ctx.webServer，
 * 因此不受 DSH `/api` 浏览器信任栅栏与 `dsh web --host 0.0.0.0` 限制牵制。
 *
 * 配置（patch 里通过 config 传入）：
 *   port       number  默认 8765（手机侧 App 端口，注意与 DSH 实例端口不同）
 *   host       string  默认 0.0.0.0
 *   defaultCwd string  新建会话的默认工作目录
 *   testHooks  boolean 等价于环境变量 DSH_RC_TEST_HOOKS=1（GAP-22）
 */
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { TokenStore } from './lib/store.mjs';
import { DshAdapter } from './lib/dsh.mjs';
import { createGateway, lanIpv4, VERSION, setStateDir } from './lib/http.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));

/**
 * 插件必须导出「带 inject 的对象」。若模块只 `export function apply`，
 * loader 的 unwrapExports 取到 apply 函数本身，函数没有 inject → 报
 * `cannot get property "sessionController" without inject`。
 */
export const plugin = {
  name: '@local/dsh-remote-gateway',
  inject: ['sessionController'],
  apply(ctx, config = {}) {
    const host = config.host ?? '0.0.0.0';
    const port = Number(config.port ?? 8765);
    const stateDir = config.stateDir ?? path.join(here, '.state');
    const defaultCwd = config.defaultCwd ?? process.cwd();
    const testHooks = config.testHooks === true || process.env.DSH_RC_TEST_HOOKS === '1';
    const lanIpv4Override = config.lanHost || process.env.DSH_RC_LAN_HOST || null;
    setStateDir(stateDir);   // 新建会话台账用

    ctx.effect(() => {
      const log = (...a) => console.log(...a);
      const tokens = new TokenStore(stateDir);
      tokens.init();
      tokens.loadIdem();
      const dsh = new DshAdapter(ctx.sessionController);
      const gw = createGateway({ dsh, tokenStore: tokens, host, port, defaultCwd, testHooks, log, lanIpv4Override });

      let closed = false;
      const ready = gw.listen()
        .then(() => {
          const ip = gw.peer;
          log(`[rc-gateway] listening http://${ip}:${port}  token(fp)=${tokens.fp}  defaultCwd=${defaultCwd}  testHooks=${testHooks}`);
          fs.mkdirSync(stateDir, { recursive: true });
          fs.writeFileSync(path.join(stateDir, 'runtime.json'), JSON.stringify({
            peer: ip, port, fp: tokens.fp, version: VERSION, testHooks, pid: process.pid,
          }, null, 2), 'utf8');
        })
        .catch((e) => {
          closed = true;
          log(`[rc-gateway] FATAL listen failed: ${e?.stack ?? e}`);
          throw e;
        });

      const disposer = () => {
        if (closed) return;
        closed = true;
        void gw.close();
      };
      // 同时暴露 disposer 与 Promise：既满足 effect 语义，也让我们能观察启动失败
      void ready.catch(() => {});
      return Object.assign(disposer, {
        then: (...a) => ready.then(...a),
        catch: (...a) => ready.catch(...a),
      });
    }, 'remote-gateway:http');
  },
};

export default plugin;

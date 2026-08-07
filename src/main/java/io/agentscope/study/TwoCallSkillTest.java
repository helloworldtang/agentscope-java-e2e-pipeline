package io.agentscope.study;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.GitSkillRepository;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 逐任务（per-call）skill 重解析的铁证测试。
 *
 * <p>同一个 JVM、同一个 GitSkillRepository 实例（= 同一个 agent 实例长生命周期），模拟两个任务：
 *
 * <ol>
 *   <li>task1 到来 → 读平台 repo 的 skill 集（这正是 HarnessSkillMiddleware.onSystemPrompt 每次
 *       call 调用的 getAllSkills，源码 HarnessSkillMiddleware.java:313）。
 *   <li>平台在 task1→task2 之间下发新 skill（往平台 git 仓 commit 一笔）。
 *   <li>task2 到来 → 同一个 repo 实例再读一次。
 * </ol>
 *
 * <p>验收：task2 读到的 skill 集 = task1 的 + 平台新下发的。证明 agent 不重建、不重启，下一个任务
 * 重新解析时就能拿到平台刚下发的 skill——「逐任务动态」成立。
 *
 * <p>跑法：先 {@code bash setup-skill-store.sh}，再
 * {@code SKILL_GIT_URL=file://.../agentscope-skill-store
 *    mvn -q exec:java -Dexec.mainClass=io.agentscope.study.TwoCallSkillTest}
 */
public class TwoCallSkillTest {

    public static void main(String[] args) throws Exception {
        String url = System.getenv("SKILL_GIT_URL");
        if (url == null || url.isBlank()) {
            System.err.println("✗ 需设 SKILL_GIT_URL（先跑 setup-skill-store.sh）");
            System.exit(1);
        }
        Path store = Paths.get(URI.create(url));

        // 模拟 agent.build() 时挂上的 repo（agent 实例长生命周期，跨任务复用同一个 repo）
        GitSkillRepository repo = new GitSkillRepository(url, null, null, "platform-git", true);

        // ===== task1 =====
        List<String> task1 = names(repo);
        System.out.println("【task1】平台 repo.getAllSkills() = " + task1);

        // ===== 平台在 task1→task2 之间下发新 skill platform-recap =====
        Path newSkill = store.resolve("skills/platform-recap/SKILL.md");
        Files.createDirectories(newSkill.getParent());
        Files.writeString(
                newSkill,
                """
                ---
                name: platform-recap
                description: 平台在 task1→task2 之间新下发的 skill B（铁证测试用）
                ---
                # platform-recap
                一句回顾。
                """);
        git(store, "add", "-A");
        git(store, "commit", "-m", "platform: task1->task2 之间下发 platform-recap");
        System.out.println("（平台已 commit 新 skill platform-recap）");

        // ===== task2：同一个 repo 实例、不重建 agent，再读一次 =====
        List<String> task2 = names(repo);
        System.out.println("【task2】平台 repo.getAllSkills() = " + task2);

        // ===== 验收 =====
        if (task2.contains("platform-recap") && !task1.contains("platform-recap")) {
            System.out.println(
                    "✓ 铁证：同一个 agent/repo 实例，task2 重新解析拿到了平台在 task1→task2 间新下发的"
                            + " platform-recap。逐任务（per-call）动态 skill 下发成立。");
        } else {
            System.out.println("✗ 未生效：task2=" + task2);
        }
        repo.close();
    }

    private static List<String> names(GitSkillRepository repo) {
        List<String> out = new ArrayList<>();
        for (AgentSkill s : repo.getAllSkills()) {
            out.add(s.getName());
        }
        out.sort(String::compareTo);
        return out;
    }

    private static void git(Path store, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(store.toString());
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.waitFor();
    }
}

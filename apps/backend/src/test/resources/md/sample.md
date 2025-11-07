下面给出一份「01背包」的 Java模板，支持：

-任意物品数 n、任意背包容积 V-仅输出最大价值-时间复杂度 O(n·V)，空间复杂度 O(V)

```javaimport java.util.*;

public class Main {
 public static void main(String[] args) {
 Scanner sc = new Scanner(System.in);
 int n = sc.nextInt(); //物品件数 int V = sc.nextInt(); //背包容量 int[] dp = new int[V +1];

 for (int i =0; i < n; i++) {
 int w = sc.nextInt(); //重量 int v = sc.nextInt(); //价值 //倒序，保证每件物品只选一次 for (int j = V; j >= w; j--) {
 dp[j] = Math.max(dp[j], dp[j - w] + v);
 }
 }
 System.out.println(dp[V]);
 }
}
```

使用说明1.把 `V`的上限按题目要求调整即可。2.若需输出具体方案，可额外记录 `pre[]`数组倒推选取路径。
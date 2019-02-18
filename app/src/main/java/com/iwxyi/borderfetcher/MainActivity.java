package com.iwxyi.borderfetcher;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

public class MainActivity extends AppCompatActivity {

    // ==== 静态变量 ====
	private static final int PHOTOGRAPH = 1;  // 拍照 requestCode
	private static final int PHOTOZOOM  = 2;  // 选择图片 requestCode
	private static final int PHOTOCROP  = 3;  // 裁剪 requestCode
	
	private static final int MAX_DIFF   = 10; // 同一颜色通道算为相同的最大差异。例如，red=95 和 red=98，不超过 10，算一样
	private static final int CHOP_DIFF  = 5;  // startChoose 方法中，把相近颜色去掉，大大增加效率（降低遍历次数）

	private static final int CHOOSING_COLOR = 0xFFFF0000; // 选中的颜色（红色）
	private static final int CHOOSING_NONE  = 0x00000000; // 未选中的颜色

    // ==== 布局控件 ====
    private TextView  mResultTv;
    private ImageView mPhotoIv;
    private TextView  mInfoTv;

    // ==== 全局变量 =====
    private Bitmap originBitmap;           // 原图（好像没什么用了）
    private Bitmap compressBitmap;         // 压缩后的图片，也是主要使用的图片，拖拽操作就在它这里盘旋
    private int compressProportion = 1;    // 压缩比例
    private int bitmapWidth, bitmapHeight; // 图片宽高（压缩后）

                                       // ==== 选择工具 ====
    private Bitmap resultBitmap;       // 结果图片（选中的地方标红）
    private Bitmap grayBitmap;         // 黑白图（暂时用不到）。如果使用，转换后需要执行 compressBitmap = grayBitmap
    private Bitmap choosedBitmap;      // 已经选择的二维矩阵，已选中为 1，未选中为 0。（因为二维boolean数组不知道怎么开，太大了会崩溃……）
    private boolean isChoosing = true; // 是否为选择模式（暂时只能添加选择，选中的位置不能撤销）

    private List<Point> movePoints = new ArrayList<>();   // 拖拽过（即将要选中）的点的集合
    private List<MyColor> moveColors = new ArrayList<>(); // 拖拽过（即将要选中）的点转换成的颜色的集合（要是我说本来是 List<Color> 你信么……）
    private List<Point> queue = new ArrayList<>();        // 广度优先搜索的队列
    private Bitmap vis;                                   // 广度优先搜索的是否遍历过的flag矩阵


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initView();
    }

    /**
     * 初始化控件，设置事件监听器
     */
    @SuppressLint("ClickableViewAccessibility")
    private void initView() {
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openAlbum();
            }
        });

        mResultTv = (TextView) findViewById(R.id.tv_result);
        mPhotoIv = (ImageView) findViewById(R.id.iv_photo);

        mPhotoIv.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        movePoints.clear();
                        moveColors.clear();
                        addMotionPoint(event);
                        break;
                    case MotionEvent.ACTION_UP:
                        if (isChoosing) { // 移动几个点，增加选择
                            startChoose();
                        } else {          // 移动几个点，删掉之前已经选中的点
                            startUnChoose();
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        addMotionPoint(event);
                        break;
                }
                return true; // 设置为 false 的话，则不会响应 move 和 up 事件
            }
        });
        mInfoTv = (TextView) findViewById(R.id.tv_info);
    }

    /**
     * 创建菜单事件
     *
     * @param menu
     * @return
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    /**
     * 菜单选项被单击事件
     *
     * @param item
     * @return
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_reset) {
            originBitmap = compressBitmap = resultBitmap = grayBitmap = choosedBitmap = null;
            mPhotoIv.setImageBitmap(null);
            compressProportion = 1;
            bitmapWidth = bitmapHeight = 0;

            mResultTv.setText("点击右下角选择一张图片");
            mInfoTv.setText("");
        }
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * 打开相册选择照片
     */
    private void openAlbum() {
        Intent intent = new Intent(Intent.ACTION_PICK, null);
        intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        startActivityForResult(intent, PHOTOZOOM);
    }

    /**
     * 裁剪图片（用不到）
     *
     * @param uri
     */
    private void startPhotoCrop(Uri uri) {
        Intent intent = new Intent("com.android.camera.action.CROP");
        intent.setDataAndType(uri, "image/*");
        intent.putExtra("crop", "true");
        // aspectX aspectY 是宽高的比例
        intent.putExtra("aspectX", 1);
        intent.putExtra("aspectY", 1);
        // outputX outputY 是裁剪图片宽高
        intent.putExtra("outputX", 300);
        intent.putExtra("outputY", 300);
        intent.putExtra("return-data", true);
        startActivityForResult(intent, PHOTOCROP);
    }

    /**
     * 打开相册完毕事件
     *
     * @param requestCode
     * @param resultCode
     * @param data
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (data == null) return;
        if (requestCode == PHOTOZOOM) {
            // ==== 压缩图片 ====
            // startPhotoCrop(data.getData()); // 不用裁剪
            Uri originalUrl = data.getData();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = false;
            originBitmap = CompressBitmapUtil.decodeBitmap(getApplicationContext(), originalUrl, options);
            compressBitmap = CompressBitmapUtil.compressBitmap(getApplicationContext(), originalUrl, 300, 300);

            mPhotoIv.setImageBitmap(compressBitmap);                 // 设置ImageView为 压缩后的图片
            compressProportion = CompressBitmapUtil.getProportion(); // 压缩比例
            bitmapWidth = CompressBitmapUtil.getPWidth();            // 压缩后的图片宽度
            bitmapHeight = CompressBitmapUtil.getPHeight();          // 压缩后的图片高度

            // ==== 设置图片选中状态 ====
            choosedBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
            //resultBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
            resultBitmap = compressBitmap.copy(Bitmap.Config.ARGB_8888,true);
            vis = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
            // 注意，这里的矩阵遍历，都是先 width 再 height，和坐标系反过来的
            for (int i = 0; i < bitmapWidth; i++)
                for (int j = 0; j < bitmapHeight; j++)
                    choosedBitmap.setPixel(i, j, CHOOSING_NONE);

            mResultTv.setText("压缩比例：" + compressProportion + "\n宽高：" + bitmapWidth + " × " + bitmapHeight);
        }
    }

    /**
     * 转换为黑白图
     * @param bitmap 原图
     * @return 黑白图
     */
    private Bitmap toGrayBitmap(Bitmap bitmap) {
        int average;
        for (int i = 0; i < bitmap.getWidth(); i++)
            for (int j = 0; j < bitmap.getHeight(); j++) {
                int color = bitmap.getPixel(i, j);
                average = Color.red(color) + Color.green(color) + Color.blue(color);
                average /= 3;
                bitmap.setPixel(i, j, average);
            }
        return bitmap;
    }

    /**
     * 针对手指划过的位置设置响应结果
     * 即获取对应位置的颜色值，并且移动到 moveColors 里面
     * 在手指松开后，压缩数组，去掉多余的颜色值
     */
    private void addMotionPoint(MotionEvent event) {
        int x = (int) event.getX(), y = (int) event.getY();
        if (x < 0 || x >= bitmapWidth || y < 0 || y >= bitmapHeight) return ;
        movePoints.add(new Point(x, y));
        //if (compressBitmap != null) // 不需要判断，为 null 的时候，ImageView 宽高为 0，不会触发 dragEvent
        for (int i = -2; i <= 2; i++)
            for (int j = -2; j <= 2; j++)
                if (x+i>=0 && x+i<bitmapWidth && y+j>=0 && y+i<bitmapHeight)
                    resultBitmap.setPixel(x+i, y+j, CHOOSING_COLOR);
        mPhotoIv.setImageBitmap(resultBitmap);
    }

    /**
     * “去重”操作，去掉颜色重复（相近）的点
     * 能够极大程度的提高选择效率
     * 目前就是暴力去除
     */
    private void chopMoveColors() {
        moveColors.clear();
        for (int i = 0; i < movePoints.size(); i++)
            moveColors.add(new MyColor(compressBitmap.getPixel(movePoints.get(i).x, movePoints.get(i).y)));
        for (int i = 0; i < moveColors.size()-1; i++) {
            MyColor colori = moveColors.get(i);
            for (int j = i + 1; j < moveColors.size(); j++) {
                if (colori.shouldChop(moveColors.get(j))) {
                    moveColors.remove(j--);
                }
            }
        }
    }

    /**
     * 使用广度优先搜索（BFS）扩展选区
     * 手指拖拽 ImageView 结束后，执行这个方法，把手指移动过的点进行判断
     * 然后在 resultBitmap 上把点给选中，就是颜色改成红色……
     * 本来想用 Queue<Point>，但奈何 Java 里面的 Queue 不一样，用不来
     */
    private void startChoose() {

        if (movePoints.size() <= 0) return ;

        // 计算运行时间
        int count = 0; // 统计增加选中的点的数量
        long startTime = System.currentTimeMillis(); //起始时间

        // ==== 裁减掉多余的颜色值 ====
        chopMoveColors();

        // ==== 使用广度优先搜索 ====
        queue.clear();
        // 初始化 vis 数组
        for (int i = 0; i < bitmapWidth; i++)
            for (int j = 0; j < bitmapHeight; j++)
                vis.setPixel(i, j, CHOOSING_NONE);
        // 初始化队列元素
        queue.addAll(movePoints);

        // 广度优先搜索
        // queue 里面的点都是之前觉得可以用，放到里面待选
        while (!queue.isEmpty()) {
            // 第一个元素
            Point point = queue.get(0);
            int x = point.x, y = point.y;
            queue.remove(0);

            // 设置各种标记
            if (vis.getPixel(x, y) != CHOOSING_NONE)
            	continue; // 本次手指拖拽已经遍历
            vis.setPixel(x, y, CHOOSING_COLOR);           // 本次已经遍历过了这个点（下次手指弹起时遍历前重新全部清零）
            choosedBitmap.setPixel(x, y, CHOOSING_COLOR); // 这张图片已经选择这个点
            resultBitmap.setPixel(x, y, CHOOSING_COLOR);  // 设置为选中颜色
            count++; // 本次选择的点的数量+1

            // 向八个方向选择（有些递归的意思）
            chooseNext(x, y, 1, -1, 0 );
            chooseNext(x, y, 1, +1, 0 );
            chooseNext(x, y, 1, 0 , -1);
            chooseNext(x, y, 1, 0 , +1);
            chooseNext(x, y, 1, -1, -1);
            chooseNext(x, y, 1, -1, +1);
            chooseNext(x, y, 1, +1, -1);
            chooseNext(x, y, 1, +1, +1);
        }

        // ==== 设置提示====
        long endTime = System.currentTimeMillis(); //结束时间
        long runTime = endTime - startTime;
        String tip = "划过的点数量：" + movePoints.size() + "个\n\n     --> 压缩后：";
        tip += moveColors.size() + "个\n";
        tip += "选中了：" + count + " 个点";
        tip += "\n运行时间：" + runTime/1000 + "秒";
        mInfoTv.setText(tip);
        mPhotoIv.setImageBitmap(resultBitmap);
    }

    /**
     * 使用深度优先搜索（DFS）扩展选区
     * 结合 BFS 与 DFS
     * @param x 搜索来源点 x
     * @param y 搜索来源点 y
     * @param d 差异距离（只向前延伸）
     * @param dx x方向，-1、0、+1
     * @param dy y方向，-1、0、+1
     */
    private boolean chooseNext(int x, int y, int d, int dx, int dy) {

        // 下面这个 数值 表示：允许跳过不一样的颜色最多6个像素（叶子间隙嘛）
        if (d > 1) return false;

        // 获取坐标点的位置
        x += d * dx;
        y += d * dy;
        if (x < 0 || x >= bitmapWidth || y < 0 || y >= bitmapHeight) return false;
        if (choosedBitmap.getPixel(x,y) != CHOOSING_NONE || vis.getPixel(x,y) != CHOOSING_NONE)
            return false; // 已经选择的，剪枝

        int color = compressBitmap.getPixel(x,y);
        if (shouldChoose(color)) {
            queue.add(new Point(x,y));
            return true;
        }
        // 向外延伸2~6共5个像素点（避免叶子间隙），根据需要调整数值与数量，不必按顺序来
        // 数量就是本方法开头的 return 语句
        // 能够扩大选择、去除间隙，但是会稍微影响那么一丢丢的效率（就是边缘判断一下，问题不大）
        // 不能增加准确性！！！可能选中错误的点（比如全都相似的天空背景）
        else if (    chooseNext(x,y,++d,dx,dy)
                //|| chooseNext(x,y,++d,dx,dy)
                //|| chooseNext(x,y,++d,dx,dy)
                //|| chooseNext(x,y,++d,dx,dy)
                //|| chooseNext(x,y,++d,dx,dy)
                ) {
            queue.add(new Point(x+d*dx,y+d*dy));
        }

        return false;
    }

    private boolean shouldChoose(int c) {
        for (int i = 0; i < moveColors.size(); i++)
            if (moveColors.get(i).isSame(c))
                return true;
        return false;
    }

    /**
     * 和 startUnChoose 方法相似
     * 不过是把之前几次选中的点（即红色）取消选择
     */
    private void startUnChoose() {
        // TODO
    }

    /**
     * Color(int) 转 MyColor 的封装
     * 避免每次都要用 Color.red 等方法从 int 中获取
     * 提高效率用。（不能直接用 Color，很操蛋啊）
     */
    class MyColor {
        private int r, g, b;

        /**
         * 从 int 值转换成颜色值
         * @param c 从Bitmap中获取到的颜色值
         */
        public MyColor(int c) {
            r = Color.red(c);
            g = Color.green(c);
            b = Color.blue(c);
            Log.i("====construct", "red:"+r+"");
        }

        /**
         * 根据 MAX_DIFF 判断两个颜色是否相似
         * 相似则 增加选中/减去选中
         * @return 颜色是否相似
         */
        public boolean isSame(int c) {
            int red = Color.red(c);
            int green = Color.green(c);
            int blue = Color.blue(c);
            return (abs(r - red) <= MAX_DIFF && abs(g - green) <= MAX_DIFF && abs(b - blue) <= MAX_DIFF);
        }

        /**
         * 根据 CHOP_DIFF 判断两个颜色是否相似
         * 此方法用在“去重”方法中
         * @param c movePoints中用来判断的一向
         * @return 是否应该去掉 List中的 c
         */
        public boolean shouldChop(MyColor c) {
            return (abs(r - c.r) <= CHOP_DIFF && abs(g - c.g) <= CHOP_DIFF && abs(b - c.b) <= CHOP_DIFF);
        }

        private int abs(int a) {
            return a < 0 ? -a : a;
        }
    }
}

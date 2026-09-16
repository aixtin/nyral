package io.github.aixtin.nyral;

import android.content.Context;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.MarkwonVisitor;
import io.noties.markwon.SpannableBuilder;
import io.noties.markwon.ext.tables.TableRowSpan;
import io.noties.markwon.ext.tables.TableSpan;
import io.noties.markwon.ext.tables.TableTheme;

/**
 * 圆角表格插件：fork 自 markwon/ext-tables TablePlugin，
 * 仅将行 span 替换为 {@link RoundedTableRowSpan}（圆角 + 选中高亮），
 * 并自带 invalidator 调度（官方 TableRowsScheduler 只认 TableRowSpan 类型，无法命中自定义 span）。
 */
public class RoundedTablePlugin extends AbstractMarkwonPlugin {

    public interface ThemeConfigure {
        void configureTheme(@NonNull TableTheme.Builder builder);
    }

    @NonNull
    public static RoundedTablePlugin create(@NonNull Context context) {
        return create(context, 1.0f);
    }

    @NonNull
    public static RoundedTablePlugin create(@NonNull Context context, float density) {
        return new RoundedTablePlugin(TableTheme.create(context), density);
    }

    @NonNull
    public static RoundedTablePlugin create(@NonNull TableTheme tableTheme) {
        return create(tableTheme, 1.0f);
    }

    @NonNull
    public static RoundedTablePlugin create(@NonNull TableTheme tableTheme, float density) {
        return new RoundedTablePlugin(tableTheme, density);
    }

    @NonNull
    public static RoundedTablePlugin create(@NonNull ThemeConfigure themeConfigure) {
        return create(themeConfigure, 1.0f);
    }

    @NonNull
    public static RoundedTablePlugin create(@NonNull ThemeConfigure themeConfigure, float density) {
        final TableTheme.Builder builder = new TableTheme.Builder();
        themeConfigure.configureTheme(builder);
        return new RoundedTablePlugin(builder.build(), density);
    }

    private final TableTheme theme;
    private final float density;
    private final TableVisitor visitor;

    RoundedTablePlugin(@NonNull TableTheme tableTheme, float density) {
        this.theme = tableTheme;
        this.density = density;
        this.visitor = new TableVisitor(tableTheme, density);
    }

    @NonNull
    public TableTheme theme() {
        return theme;
    }

    @Override
    public void configureParser(@NonNull Parser.Builder builder) {
        builder.extensions(Collections.singleton(TablesExtension.create()));
    }

    @Override
    public void configureVisitor(@NonNull MarkwonVisitor.Builder builder) {
        visitor.configure(builder);
    }

    @Override
    public void beforeRender(@NonNull Node node) {
        visitor.clear();
    }

    @Override
    public void beforeSetText(@NonNull TextView textView, @NonNull Spanned markdown) {
        unschedule(textView);
    }

    @Override
    public void afterSetText(@NonNull TextView textView) {
        schedule(textView);
    }

    // ---------- invalidator 调度（对齐 TableRowsScheduler，仅命中 RoundedTableRowSpan） ----------

    private static void schedule(@NonNull final TextView view) {
        final Object[] spans = extract(view);
        if (spans != null && spans.length > 0) {

            if (view.getTag(io.noties.markwon.ext.tables.R.id.markwon_tables_scheduler) == null) {
                final View.OnAttachStateChangeListener listener = new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        unschedule(view);
                        view.removeOnAttachStateChangeListener(this);
                        view.setTag(io.noties.markwon.ext.tables.R.id.markwon_tables_scheduler, null);
                    }
                };
                view.addOnAttachStateChangeListener(listener);
                view.setTag(io.noties.markwon.ext.tables.R.id.markwon_tables_scheduler, listener);
            }

            final RoundedTableRowSpan.Invalidator invalidator = new RoundedTableRowSpan.Invalidator() {
                final Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        view.setText(view.getText());
                    }
                };

                @Override
                public void invalidate() {
                    view.removeCallbacks(runnable);
                    view.post(runnable);
                }
            };

            for (Object span : spans) {
                ((RoundedTableRowSpan) span).invalidator(invalidator);
            }
        }
    }

    private static void unschedule(@NonNull TextView view) {
        final Object[] spans = extract(view);
        if (spans != null && spans.length > 0) {
            for (Object span : spans) {
                ((RoundedTableRowSpan) span).invalidator(null);
            }
        }
    }

    @Nullable
    private static Object[] extract(@NonNull TextView view) {
        final CharSequence text = view.getText();
        if (!TextUtils.isEmpty(text) && text instanceof Spanned) {
            return ((Spanned) text).getSpans(0, text.length(), RoundedTableRowSpan.class);
        }
        return null;
    }

    // ---------- visitor（对齐官方 TableVisitor，仅替换 span 工厂） ----------

    private static class TableVisitor {

        private final TableTheme tableTheme;
        private final float density;

        private List<TableRowSpan.Cell> pendingTableRow;
        private boolean tableRowIsHeader;
        private int tableRows;

        TableVisitor(@NonNull TableTheme tableTheme, float density) {
            this.tableTheme = tableTheme;
            this.density = density;
        }

        void clear() {
            pendingTableRow = null;
            tableRowIsHeader = false;
            tableRows = 0;
        }

        void configure(@NonNull MarkwonVisitor.Builder builder) {
            builder
                    .on(TableBlock.class, new MarkwonVisitor.NodeVisitor<TableBlock>() {
                        @Override
                        public void visit(@NonNull MarkwonVisitor visitor, @NonNull TableBlock node) {
                            visitor.blockStart(node);

                            final int length = visitor.length();

                            visitor.visitChildren(node);

                            visitor.setSpans(length, new TableSpan());

                            visitor.blockEnd(node);
                        }
                    })
                    .on(TableBody.class, new MarkwonVisitor.NodeVisitor<TableBody>() {
                        @Override
                        public void visit(@NonNull MarkwonVisitor visitor, @NonNull TableBody node) {
                            visitor.visitChildren(node);
                            tableRows = 0;
                        }
                    })
                    .on(TableRow.class, new MarkwonVisitor.NodeVisitor<TableRow>() {
                        @Override
                        public void visit(@NonNull MarkwonVisitor visitor, @NonNull TableRow node) {
                            visitRow(visitor, node);
                        }
                    })
                    .on(TableHead.class, new MarkwonVisitor.NodeVisitor<TableHead>() {
                        @Override
                        public void visit(@NonNull MarkwonVisitor visitor, @NonNull TableHead node) {
                            visitRow(visitor, node);
                        }
                    })
                    .on(TableCell.class, new MarkwonVisitor.NodeVisitor<TableCell>() {
                        @Override
                        public void visit(@NonNull MarkwonVisitor visitor, @NonNull TableCell tableCell) {

                            final int length = visitor.length();

                            visitor.visitChildren(tableCell);

                            if (pendingTableRow == null) {
                                pendingTableRow = new ArrayList<>(2);
                            }

                            pendingTableRow.add(new TableRowSpan.Cell(
                                    tableCellAlignment(tableCell.getAlignment()),
                                    visitor.builder().removeFromEnd(length)
                            ));

                            tableRowIsHeader = tableCell.isHeader();
                        }
                    });
        }

        private void visitRow(@NonNull MarkwonVisitor visitor, @NonNull Node node) {

            final int length = visitor.length();

            visitor.visitChildren(node);

            if (pendingTableRow != null) {

                final SpannableBuilder builder = visitor.builder();

                final boolean addNewLine;
                {
                    final int builderLength = builder.length();
                    addNewLine = builderLength > 0
                            && '\n' != builder.charAt(builderLength - 1);
                }

                if (addNewLine) {
                    visitor.forceNewLine();
                }

                // Replace table char with non-breakable space
                builder.append('\u00a0');

                final Object span = new RoundedTableRowSpan(
                        tableTheme,
                        pendingTableRow,
                        tableRowIsHeader,
                        tableRows % 2 == 1,
                        density);

                tableRows = tableRowIsHeader
                        ? 0
                        : tableRows + 1;

                visitor.setSpans(addNewLine ? length + 1 : length, span);

                pendingTableRow = null;
            }
        }

        @TableRowSpan.Alignment
        private static int tableCellAlignment(TableCell.Alignment alignment) {
            final int out;
            if (alignment != null) {
                switch (alignment) {
                    case CENTER:
                        out = TableRowSpan.ALIGN_CENTER;
                        break;
                    case RIGHT:
                        out = TableRowSpan.ALIGN_RIGHT;
                        break;
                    default:
                        out = TableRowSpan.ALIGN_LEFT;
                        break;
                }
            } else {
                out = TableRowSpan.ALIGN_LEFT;
            }
            return out;
        }
    }
}

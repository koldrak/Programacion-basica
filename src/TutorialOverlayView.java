import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TutorialOverlayView es un contenedor a pantalla completa que pinta un fondo translúcido
 * y recorta la zona del objetivo resaltado usando PorterDuffXfermode. Mientras está visible,
 * bloquea la interacción del resto de la UI y reenvía los eventos táctiles únicamente al
 * elemento destacado, permitiendo guiar paso a paso un flujo concreto.
 */
public class TutorialOverlayView extends FrameLayout {

    /** Callback para saber cuándo finaliza el tutorial. */
    public interface OnTutorialFinishedListener {
        void onTutorialFinished();
    }

    /** Callback para enterarse cuando se cambia de paso. */
    public interface OnStepChangedListener {
        void onStepChanged(Step step, int position, int totalSteps);
    }

    /** Condición que determina si el paso puede darse por completado. */
    public interface StepCompletionCondition {
        boolean isSatisfied();
    }

    /** Representa un paso individual del tutorial. */
    public static class Step {
        final View target;
        final CharSequence title;
        final CharSequence description;
        final boolean forwardTargetEvents;
        final boolean autoAdvanceOnTargetClick;
        final float highlightPaddingPx;
        final float cornerRadiusPx;
        final StepCompletionCondition completionCondition;

        Step(Builder builder) {
            this.target = builder.target;
            this.title = builder.title;
            this.description = builder.description;
            this.forwardTargetEvents = builder.forwardTargetEvents;
            this.autoAdvanceOnTargetClick = builder.autoAdvanceOnTargetClick;
            this.highlightPaddingPx = builder.highlightPaddingPx >= 0
                    ? builder.highlightPaddingPx
                    : dpToPx(builder.target, 16);
            this.cornerRadiusPx = builder.cornerRadiusPx >= 0
                    ? builder.cornerRadiusPx
                    : dpToPx(builder.target, 12);
            this.completionCondition = builder.completionCondition;
        }

        public View getTarget() {
            return target;
        }

        public CharSequence getTitle() {
            return title;
        }

        public CharSequence getDescription() {
            return description;
        }

        public StepCompletionCondition getCompletionCondition() {
            return completionCondition;
        }

        public static class Builder {
            private final View target;
            private CharSequence title;
            private CharSequence description;
            private boolean forwardTargetEvents = true;
            private boolean autoAdvanceOnTargetClick = true;
            private float highlightPaddingPx = -1f;
            private float cornerRadiusPx = -1f;
            private StepCompletionCondition completionCondition;

            public Builder(View target) {
                if (target == null) {
                    throw new IllegalArgumentException("El target del paso no puede ser nulo");
                }
                this.target = target;
            }

            public Builder setTitle(CharSequence title) {
                this.title = title;
                return this;
            }

            public Builder setDescription(CharSequence description) {
                this.description = description;
                return this;
            }

            public Builder setForwardTargetEvents(boolean forwardTargetEvents) {
                this.forwardTargetEvents = forwardTargetEvents;
                return this;
            }

            public Builder setAutoAdvanceOnTargetClick(boolean autoAdvanceOnTargetClick) {
                this.autoAdvanceOnTargetClick = autoAdvanceOnTargetClick;
                return this;
            }

            public Builder setHighlightPaddingDp(float dp) {
                this.highlightPaddingPx = dpToPx(target, dp);
                return this;
            }

            public Builder setCornerRadiusDp(float dp) {
                this.cornerRadiusPx = dpToPx(target, dp);
                return this;
            }

            public Builder setCompletionCondition(StepCompletionCondition completionCondition) {
                this.completionCondition = completionCondition;
                return this;
            }

            public Step build() {
                if (title == null && description == null) {
                    throw new IllegalStateException("El paso debe tener título o descripción");
                }
                return new Step(this);
            }
        }
    }

    private static float dpToPx(View view, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                view.getResources().getDisplayMetrics());
    }

    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF highlightRect = new RectF();
    private final Path highlightPath = new Path();
    private final Rect tmpRect = new Rect();
    private final int[] tmpTargetLocation = new int[2];
    private final int[] tmpOverlayLocation = new int[2];

    private final LinearLayout tooltipContainer;
    private final TextView tooltipTitle;
    private final TextView tooltipDescription;

    private final List<Step> steps = new ArrayList<>();
    private Step currentStep;
    private int currentIndex = -1;
    private boolean running = false;

    private OnTutorialFinishedListener finishedListener;
    private OnStepChangedListener stepChangedListener;
    private final AtomicBoolean tooltipVisible = new AtomicBoolean(false);

    private final ViewTreeObserver.OnGlobalLayoutListener targetLayoutListener = this::updateHighlight;

    public TutorialOverlayView(Context context) {
        this(context, null);
    }

    public TutorialOverlayView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TutorialOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        scrimPaint.setColor(0xCC101322);

        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dpToPx(context, 2));
        strokePaint.setColor(0xFFFFFFFF);
        strokePaint.setAlpha(160);

        tooltipContainer = new LinearLayout(context);
        tooltipContainer.setOrientation(LinearLayout.VERTICAL);
        tooltipContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        tooltipContainer.setBackground(createTooltipBackground());
        int padding = (int) dpToPx(context, 16);
        tooltipContainer.setPadding(padding, padding, padding, padding);

        tooltipTitle = new TextView(context);
        tooltipTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tooltipTitle.setTextColor(0xFF0B1F3A);
        tooltipTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tooltipTitle.setGravity(Gravity.CENTER_HORIZONTAL);

        tooltipDescription = new TextView(context);
        tooltipDescription.setTextColor(0xFF23324A);
        tooltipDescription.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tooltipDescription.setGravity(Gravity.CENTER);

        tooltipContainer.addView(tooltipTitle,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = (int) dpToPx(context, 8);
        tooltipContainer.addView(tooltipDescription, descParams);

        addView(tooltipContainer, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        tooltipContainer.setAlpha(0f);
        setVisibility(GONE);
    }

    private static float dpToPx(Context context, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics());
    }

    private GradientDrawable createTooltipBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xFFF3F6FF);
        drawable.setCornerRadius(dpToPx(getContext(), 12));
        drawable.setStroke((int) dpToPx(getContext(), 1), 0x4423324A);
        return drawable;
    }

    public void setOnTutorialFinishedListener(OnTutorialFinishedListener listener) {
        this.finishedListener = listener;
    }

    public void setOnStepChangedListener(OnStepChangedListener listener) {
        this.stepChangedListener = listener;
    }

    public void setSteps(List<Step> steps) {
        this.steps.clear();
        if (steps != null) {
            this.steps.addAll(steps);
        }
        currentIndex = -1;
        currentStep = null;
    }

    public List<Step> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public void start() {
        if (steps.isEmpty()) {
            return;
        }
        if (getParent() == null) {
            throw new IllegalStateException("Debes agregar el TutorialOverlayView al contenedor principal antes de iniciarlo");
        }
        running = true;
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(180).start();
        advanceTo(0);
    }

    public void dismiss() {
        if (!running) {
            return;
        }
        running = false;
        removeTargetLayoutListener();
        ValueAnimator fadeOut = ValueAnimator.ofFloat(1f, 0f);
        fadeOut.setDuration(200);
        fadeOut.addUpdateListener(anim -> setAlpha((Float) anim.getAnimatedValue()));
        fadeOut.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setVisibility(GONE);
                setAlpha(1f);
                tooltipContainer.setAlpha(0f);
                highlightRect.setEmpty();
                currentStep = null;
                currentIndex = -1;
                invalidate();
                if (finishedListener != null) {
                    finishedListener.onTutorialFinished();
                }
            }
        });
        fadeOut.start();
    }

    private void advanceTo(int index) {
        if (index >= steps.size()) {
            dismiss();
            return;
        }
        Step next = steps.get(index);
        if (next.target.getWindowToken() == null) {
            next.target.post(() -> advanceTo(index));
            return;
        }
        removeTargetLayoutListener();
        currentStep = next;
        currentIndex = index;
        next.target.getViewTreeObserver().addOnGlobalLayoutListener(targetLayoutListener);
        updateHighlight();
        updateTooltipContent();
        if (stepChangedListener != null) {
            stepChangedListener.onStepChanged(currentStep, currentIndex, steps.size());
        }
    }

    public void nextStep() {
        if (!running) {
            return;
        }
        advanceTo(currentIndex + 1);
    }

    public Step getCurrentStep() {
        return currentStep;
    }

    public boolean isRunning() {
        return running;
    }

    private void updateTooltipContent() {
        if (currentStep == null) {
            return;
        }
        tooltipTitle.setVisibility(currentStep.title == null ? GONE : VISIBLE);
        if (currentStep.title != null) {
            tooltipTitle.setText(currentStep.title);
        }
        tooltipDescription.setVisibility(currentStep.description == null ? GONE : VISIBLE);
        if (currentStep.description != null) {
            tooltipDescription.setText(currentStep.description);
        }
        if (tooltipVisible.compareAndSet(false, true)) {
            tooltipContainer.setScaleX(0.9f);
            tooltipContainer.setScaleY(0.9f);
            tooltipContainer.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .start();
        }
        post(this::positionTooltip);
    }

    private void removeTargetLayoutListener() {
        if (currentStep != null && currentStep.target != null) {
            currentStep.target.getViewTreeObserver().removeOnGlobalLayoutListener(targetLayoutListener);
        }
    }

    private void updateHighlight() {
        if (currentStep == null) {
            return;
        }
        View target = currentStep.target;
        if (!target.isShown()) {
            highlightRect.setEmpty();
            invalidate();
            return;
        }
        target.getGlobalVisibleRect(tmpRect);
        getLocationOnScreen(tmpOverlayLocation);
        float left = tmpRect.left - tmpOverlayLocation[0] - currentStep.highlightPaddingPx;
        float top = tmpRect.top - tmpOverlayLocation[1] - currentStep.highlightPaddingPx;
        float right = tmpRect.right - tmpOverlayLocation[0] + currentStep.highlightPaddingPx;
        float bottom = tmpRect.bottom - tmpOverlayLocation[1] + currentStep.highlightPaddingPx;
        highlightRect.set(left, top, right, bottom);
        highlightPath.reset();
        highlightPath.addRoundRect(highlightRect, currentStep.cornerRadiusPx,
                currentStep.cornerRadiusPx, Path.Direction.CW);
        invalidate();
        positionTooltip();
    }

    private void positionTooltip() {
        if (currentStep == null || tooltipContainer.getWidth() == 0) {
            return;
        }
        float margin = dpToPx(getContext(), 12);
        float tooltipWidth = tooltipContainer.getMeasuredWidth();
        float tooltipHeight = tooltipContainer.getMeasuredHeight();
        if (tooltipWidth == 0 || tooltipHeight == 0) {
            tooltipContainer.measure(MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.AT_MOST));
            tooltipWidth = tooltipContainer.getMeasuredWidth();
            tooltipHeight = tooltipContainer.getMeasuredHeight();
        }
        float centerX = highlightRect.centerX();
        float x = centerX - (tooltipWidth / 2f);
        float y;
        boolean placeAbove = highlightRect.bottom + tooltipHeight + margin > getHeight()
                && highlightRect.top - tooltipHeight - margin >= 0;
        if (placeAbove) {
            y = highlightRect.top - tooltipHeight - margin;
        } else {
            y = highlightRect.bottom + margin;
            if (y + tooltipHeight > getHeight() - margin) {
                y = getHeight() - tooltipHeight - margin;
            }
        }
        if (x < margin) {
            x = margin;
        } else if (x + tooltipWidth > getWidth() - margin) {
            x = getWidth() - tooltipWidth - margin;
        }
        if (y < margin) {
            y = highlightRect.bottom + margin;
        }
        tooltipContainer.setX(x);
        tooltipContainer.setY(y);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeTargetLayoutListener();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!running) {
            return;
        }
        int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
        if (!highlightRect.isEmpty()) {
            canvas.drawPath(highlightPath, clearPaint);
            canvas.drawPath(highlightPath, strokePaint);
        }
        canvas.restoreToCount(save);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!running || currentStep == null) {
            return super.dispatchTouchEvent(ev);
        }
        if (currentStep.target == null) {
            return true;
        }
        float rawX = ev.getRawX();
        float rawY = ev.getRawY();
        currentStep.target.getLocationOnScreen(tmpTargetLocation);
        float left = tmpTargetLocation[0] - currentStep.highlightPaddingPx;
        float top = tmpTargetLocation[1] - currentStep.highlightPaddingPx;
        float right = left + currentStep.target.getWidth() + currentStep.highlightPaddingPx * 2;
        float bottom = top + currentStep.target.getHeight() + currentStep.highlightPaddingPx * 2;
        boolean insideHighlight = rawX >= left && rawX <= right && rawY >= top && rawY <= bottom;
        if (insideHighlight) {
            if (currentStep.forwardTargetEvents) {
                MotionEvent transformed = MotionEvent.obtain(ev);
                float offsetX = -tmpTargetLocation[0];
                float offsetY = -tmpTargetLocation[1];
                transformed.offsetLocation(offsetX, offsetY);
                boolean handled = currentStep.target.dispatchTouchEvent(transformed);
                transformed.recycle();
                if (currentStep.autoAdvanceOnTargetClick
                        && ev.getAction() == MotionEvent.ACTION_UP
                        && isCurrentStepCompleted()) {
                    nextStep();
                }
                return handled || currentStep.autoAdvanceOnTargetClick;
            } else {
                if (currentStep.autoAdvanceOnTargetClick && ev.getAction() == MotionEvent.ACTION_UP) {
                    if (isCurrentStepCompleted()) {
                        nextStep();
                    }
                }
                return true;
            }
        }
        return true;
    }

    private boolean isCurrentStepCompleted() {
        return currentStep == null
                || currentStep.completionCondition == null
                || currentStep.completionCondition.isSatisfied();
    }

    /**
     * Permite reevaluar la condición del paso en curso (p.ej. tras completar una acción
     * asincrónica). Si está satisfecha se avanza automáticamente al siguiente paso.
     */
    public void notifyStepProgressChanged() {
        if (!running || currentStep == null) {
            return;
        }
        if (isCurrentStepCompleted()) {
            post(this::nextStep);
        }
    }

    /**
     * Coordenadas en pantalla del área resaltada actual.
     */
    public RectF getHighlightRect() {
        return new RectF(highlightRect);
    }

    /**
     * Controlador específico para el flujo básico "Crear curso" -> "Crear alumno".
     * Gestiona las condiciones de avance y expone métodos para marcar cuando cada acción
     * se completó realmente.
     */
    public static class CourseOnboardingController {
        private static final String DEFAULT_COMPLETION_FLAG = "course_onboarding_completed";
        private final TutorialOverlayView overlay;
        private final AtomicBoolean courseCreated = new AtomicBoolean(false);
        private final AtomicBoolean studentCreated = new AtomicBoolean(false);
        private final SharedPreferences prefs;
        private final String completionFlagKey;
        private OnTutorialFinishedListener externalFinishedListener;

        public CourseOnboardingController(TutorialOverlayView overlay,
                                          View createCourseButton,
                                          View createStudentButton) {
            this(overlay, createCourseButton, createStudentButton, null, null);
        }

        public CourseOnboardingController(TutorialOverlayView overlay,
                                          View createCourseButton,
                                          View createStudentButton,
                                          SharedPreferences preferences) {
            this(overlay, createCourseButton, createStudentButton, preferences, null);
        }

        public CourseOnboardingController(TutorialOverlayView overlay,
                                          View createCourseButton,
                                          View createStudentButton,
                                          SharedPreferences preferences,
                                          String completionFlagKey) {
            this.overlay = overlay;
            this.prefs = preferences;
            this.completionFlagKey = completionFlagKey != null
                    ? completionFlagKey
                    : DEFAULT_COMPLETION_FLAG;
            Step createCourse = new Step.Builder(createCourseButton)
                    .setTitle("Crea tu primer curso")
                    .setDescription("Pulsa aquí para configurar un nuevo curso.")
                    .setHighlightPaddingDp(12)
                    .setCompletionCondition(courseCreated::get)
                    .build();
            Step createStudent = new Step.Builder(createStudentButton)
                    .setTitle("Agrega un alumno")
                    .setDescription("Una vez creado el curso, utiliza este botón para matricular a tu primer alumno.")
                    .setHighlightPaddingDp(12)
                    .setCompletionCondition(() -> courseCreated.get() && studentCreated.get())
                    .build();
            overlay.setSteps(Arrays.asList(createCourse, createStudent));
            overlay.setOnTutorialFinishedListener(() -> {
                if (prefs != null && courseCreated.get() && studentCreated.get()) {
                    prefs.edit().putBoolean(this.completionFlagKey, true).apply();
                }
                if (externalFinishedListener != null) {
                    externalFinishedListener.onTutorialFinished();
                }
            });
        }

        /** Marca que el curso fue creado exitosamente. */
        public void markCourseCreated() {
            if (courseCreated.compareAndSet(false, true)) {
                overlay.notifyStepProgressChanged();
            }
        }

        /** Marca que se registró al menos un alumno dentro del curso. */
        public void markStudentCreated() {
            if (studentCreated.compareAndSet(false, true)) {
                overlay.notifyStepProgressChanged();
            }
        }

        public void start() {
            if (prefs != null && prefs.getBoolean(completionFlagKey, false)) {
                return;
            }
            overlay.start();
        }

        public void dismiss() {
            overlay.dismiss();
        }

        /**
         * Permite consultar si el usuario ya completó el tutorial previamente.
         */
        public boolean isCompleted() {
            return prefs != null && prefs.getBoolean(completionFlagKey, false);
        }

        /**
         * Restablece el estado persistido para volver a mostrar el tutorial en el siguiente arranque.
         */
        public void resetProgress() {
            if (prefs != null) {
                prefs.edit().remove(completionFlagKey).apply();
            }
            courseCreated.set(false);
            studentCreated.set(false);
        }

        /**
         * Registra un listener adicional que será notificado cuando se cierre el tutorial.
         */
        public void setOnTutorialFinishedListener(OnTutorialFinishedListener listener) {
            this.externalFinishedListener = listener;
        }
    }
}
